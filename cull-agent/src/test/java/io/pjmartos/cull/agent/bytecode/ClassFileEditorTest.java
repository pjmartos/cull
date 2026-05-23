package io.pjmartos.cull.agent.bytecode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.core.ClassFileMetadata;
import io.pjmartos.cull.core.ClassFileReader;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.Test;

class ClassFileEditorTest {

  static class Sample {
    public String run(String s) {
      return s + "x";
    }
  }

  @Test
  void noTargetsReturnsParseable() throws IOException {
    byte[] bytes = readClassBytes(Sample.class);
    byte[] out = ClassFileEditor.instrument(bytes, List.of(), (n, d, a, ms, ml, c, cp, r) -> c);
    ClassFileMetadata m = ClassFileReader.parse(out);
    assertEquals("io/pjmartos/cull/agent/bytecode/ClassFileEditorTest$Sample", m.thisClass());
  }

  @Test
  void rewriteAddsConstantsAndKeepsClassParseable() throws IOException {
    byte[] bytes = readClassBytes(Sample.class);
    byte[] out =
        ClassFileEditor.instrument(
            bytes,
            List.of(
                new ClassFileEditor.MethodTarget("run", "(Ljava/lang/String;)Ljava/lang/String;")),
            (name, desc, access, maxStack, maxLocals, code, cp, outResult) -> {
              int ref =
                  cp.methodRef(
                      "io/pjmartos/cull/agent/RecorderHolder",
                      "recordResourceObject",
                      "(Ljava/lang/Object;)V");
              byte[] prologue =
                  new byte[] {
                    (byte) 0x2B, (byte) 0xB8, (byte) ((ref >>> 8) & 0xFF), (byte) (ref & 0xFF)
                  };
              byte[] result = new byte[prologue.length + code.length];
              System.arraycopy(prologue, 0, result, 0, prologue.length);
              System.arraycopy(code, 0, result, prologue.length, code.length);
              outResult.rewrote = true;
              outResult.newMaxStack = maxStack + 1;
              return result;
            });
    ClassFileMetadata m = ClassFileReader.parse(out);
    assertTrue(m.referencedClasses().contains("io/pjmartos/cull/agent/RecorderHolder"));
  }

  private byte[] readClassBytes(Class<?> c) throws IOException {
    String resource = "/" + c.getName().replace('.', '/') + ".class";
    try (InputStream in = c.getResourceAsStream(resource)) {
      return in.readAllBytes();
    }
  }
}
