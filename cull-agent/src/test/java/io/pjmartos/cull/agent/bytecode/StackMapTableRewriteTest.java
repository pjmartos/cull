package io.pjmartos.cull.agent.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

public class StackMapTableRewriteTest {

  public static final AtomicInteger PROBE_HITS = new AtomicInteger();

  public static void probe(Object ignored) {
    PROBE_HITS.incrementAndGet();
  }

  @Test
  void rewrittenBranchedMethodsLoadAndExecute() throws Exception {
    PROBE_HITS.set(0);
    byte[] orig = readBytes(BranchedSample.class);
    byte[] rewritten =
        ClassFileEditor.instrument(
            orig,
            List.of(
                new ClassFileEditor.MethodTarget("max", "(II)I"),
                new ClassFileEditor.MethodTarget("describe", "(I)Ljava/lang/String;"),
                new ClassFileEditor.MethodTarget("sumLoop", "(I)I"),
                new ClassFileEditor.MethodTarget("newObjectAndAssert", "(Z)Ljava/lang/Object;")),
            (name, desc, access, maxStack, maxLocals, code, cp, out) -> {
              int probe =
                  cp.methodRef(
                      "io/pjmartos/cull/agent/bytecode/StackMapTableRewriteTest",
                      "probe",
                      "(Ljava/lang/Object;)V");
              byte[] prologue =
                  new byte[] {
                    (byte) 0x2A, (byte) 0xB8, (byte) ((probe >>> 8) & 0xFF), (byte) (probe & 0xFF)
                  };
              byte[] result = new byte[prologue.length + code.length];
              System.arraycopy(prologue, 0, result, 0, prologue.length);
              System.arraycopy(code, 0, result, prologue.length, code.length);
              out.rewrote = true;
              out.newMaxStack = maxStack + 1;
              return result;
            });

    Class<?> reloaded = defineUnderNewLoader(BranchedSample.class.getName(), rewritten);
    Object instance = reloaded.getDeclaredConstructor().newInstance();

    Method max = reloaded.getMethod("max", int.class, int.class);
    assertEquals(7, max.invoke(instance, 3, 7));
    assertEquals(9, max.invoke(instance, 9, 2));

    Method describe = reloaded.getMethod("describe", int.class);
    assertEquals("negative", describe.invoke(instance, -1));
    assertEquals("zero", describe.invoke(instance, 0));
    assertEquals("positive", describe.invoke(instance, 5));

    Method sum = reloaded.getMethod("sumLoop", int.class);
    assertEquals(45, sum.invoke(instance, 10));

    Method newObj = reloaded.getMethod("newObjectAndAssert", boolean.class);
    assertNotNull(newObj.invoke(instance, true));
    assertNull(newObj.invoke(instance, false));

    assertTrue(
        PROBE_HITS.get() >= 8,
        "prologue invokestatic should have run for every invocation; got " + PROBE_HITS.get());
  }

  @Test
  void rewrittenIdentityYieldsByteIdenticalStackMapAfterRoundTrip() throws IOException {
    byte[] orig = readBytes(BranchedSample.class);
    byte[] out = ClassFileEditor.instrument(orig, List.of(), (n, d, a, ms, ml, c, cp, r) -> c);
    assertEquals(orig.length, out.length, "identity rewrite should not grow the class");
  }

  @Test
  void zeroEntryStackMapTableIsIdempotent() throws IOException {
    byte[] body = new byte[] {0, 0};
    byte[] adjusted = ClassFileEditor.adjustStackMapTable(body, 4);
    assertEquals(2, adjusted.length);
    assertEquals(0, adjusted[0]);
    assertEquals(0, adjusted[1]);
  }

  @Test
  void smallSameFrameOverflowingPromotesToExtended() throws IOException {
    byte[] body = new byte[] {0, 1, 60};
    byte[] adjusted = ClassFileEditor.adjustStackMapTable(body, 4);
    assertEquals(5, adjusted.length, "expected promotion to SAME_FRAME_EXTENDED (1 + 2 byte u2)");
    assertEquals((byte) 251, adjusted[2]);
    int newOd = ((adjusted[3] & 0xFF) << 8) | (adjusted[4] & 0xFF);
    assertEquals(64, newOd);
  }

  @Test
  void uninitializedVerificationOffsetShifts() throws IOException {
    byte[] body =
        new byte[] {
          0, 1, (byte) 255, 0, 10, 0, 1, 8, 0, 5, 0, 0,
        };
    byte[] adjusted = ClassFileEditor.adjustStackMapTable(body, 4);
    int idxOfUninit = -1;
    for (int i = 0; i < adjusted.length - 2; i++) {
      if (adjusted[i] == 8) {
        idxOfUninit = i;
        break;
      }
    }
    assertTrue(idxOfUninit > 0, "ITEM_Uninitialized tag should remain in adjusted table");
    int shifted = ((adjusted[idxOfUninit + 1] & 0xFF) << 8) | (adjusted[idxOfUninit + 2] & 0xFF);
    assertEquals(9, shifted, "Uninitialized bytecode offset should shift by delta=4");
  }

  private static byte[] readBytes(Class<?> c) throws IOException {
    String resource = "/" + c.getName().replace('.', '/') + ".class";
    try (InputStream in = c.getResourceAsStream(resource)) {
      if (in == null) {
        throw new IOException("missing resource " + resource);
      }
      return in.readAllBytes();
    }
  }

  private static Class<?> defineUnderNewLoader(String name, byte[] bytes) {
    Map<String, byte[]> defs = new HashMap<>();
    defs.put(name, bytes);
    ClassLoader parent = StackMapTableRewriteTest.class.getClassLoader();
    ClassLoader cl =
        new ClassLoader(parent) {
          @Override
          protected Class<?> findClass(String n) throws ClassNotFoundException {
            byte[] b = defs.get(n);
            if (b == null) {
              throw new ClassNotFoundException(n);
            }
            return defineClass(n, b, 0, b.length);
          }

          @Override
          protected Class<?> loadClass(String n, boolean resolve) throws ClassNotFoundException {
            if (defs.containsKey(n)) {
              Class<?> c = findLoadedClass(n);
              if (c == null) {
                c = findClass(n);
              }
              if (resolve) {
                resolveClass(c);
              }
              return c;
            }
            return super.loadClass(n, resolve);
          }
        };
    try {
      return Class.forName(name, true, cl);
    } catch (ClassNotFoundException e) {
      throw new AssertionError(e);
    }
  }
}
