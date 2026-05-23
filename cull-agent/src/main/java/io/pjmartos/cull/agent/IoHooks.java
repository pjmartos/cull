package io.pjmartos.cull.agent;

import io.pjmartos.cull.agent.bytecode.ClassFileEditor;
import io.pjmartos.cull.agent.bytecode.ConstantPoolBuilder;
import java.io.IOException;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public final class IoHooks {

  private IoHooks() {}

  public static boolean install(Instrumentation inst) {
    Map<String, List<Spec>> byClass = buildSpecs();
    if (!probeAllTargets(byClass)) {
      return false;
    }
    Class<?>[] candidates = resolveCandidates(byClass.keySet());
    if (candidates.length == 0) {
      return false;
    }
    AtomicBoolean rewriteFailed = new AtomicBoolean(false);
    ClassFileTransformer xf =
        new ClassFileTransformer() {
          // null returns signal "no transformation" per Instrumentation API contract.
          @Override
          @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
          public byte[] transform(
              ClassLoader loader,
              String name,
              Class<?> redefined,
              java.security.ProtectionDomain pd,
              byte[] classfileBuffer) {
            if (name == null) return null;
            List<Spec> specs = byClass.get(name);
            if (specs == null || specs.isEmpty()) return null;
            try {
              return rewrite(classfileBuffer, specs);
            } catch (Throwable t) {
              rewriteFailed.set(true);
              if (Boolean.getBoolean("cull.iohooks.debug")) {
                System.err.println("[cull] iohooks: rewrite failed for " + name + ": " + t);
              }
              return null;
            }
          }
        };
    inst.addTransformer(xf, true);
    int succeeded = 0;
    boolean debug = Boolean.getBoolean("cull.iohooks.debug");
    for (Class<?> c : candidates) {
      try {
        inst.retransformClasses(c);
        succeeded++;
      } catch (UnmodifiableClassException | RuntimeException | LinkageError e) {
        if (debug) {
          System.err.println("[cull] iohooks: retransform failed for " + c.getName() + ": " + e);
        }
      }
    }
    // I/O hook instrumentation is all-or-nothing. A partially hooked JVM would
    // silently miss reads through the un-hooked methods with no diagnostic,
    // under-attributing dependencies. If any target class failed to retransform
    // or had its rewrite rejected, back the transformer out and report failure
    // so the caller enters the sound (over-approximating) degraded mode.
    if (succeeded != candidates.length || rewriteFailed.get()) {
      inst.removeTransformer(xf);
      return false;
    }
    return true;
  }

  public static boolean probeAllTargetsForCurrentJvm() {
    return probeAllTargets(buildSpecs());
  }

  static boolean probeAllTargets(Map<String, List<Spec>> byClass) {
    for (Map.Entry<String, List<Spec>> e : byClass.entrySet()) {
      Class<?> c;
      try {
        c = Class.forName(e.getKey().replace('/', '.'), false, ClassLoader.getSystemClassLoader());
      } catch (Throwable t) {
        return false;
      }
      for (Spec s : e.getValue()) {
        if (!hasMethodOrConstructor(c, s.method, s.descriptor)) {
          return false;
        }
      }
    }
    return true;
  }

  private static boolean hasMethodOrConstructor(Class<?> c, String name, String descriptor) {
    boolean isInit = "<init>".equals(name);
    java.lang.reflect.Executable[] members =
        isInit ? c.getDeclaredConstructors() : c.getDeclaredMethods();
    for (java.lang.reflect.Executable m : members) {
      if (!isInit && !name.equals(m.getName())) continue;
      String d = methodDescriptor(m);
      if (descriptor.equals(d)) return true;
    }
    return false;
  }

  static String methodDescriptor(java.lang.reflect.Executable m) {
    StringBuilder sb = new StringBuilder("(");
    for (Class<?> p : m.getParameterTypes()) {
      sb.append(typeDescriptor(p));
    }
    sb.append(')');
    if (m instanceof java.lang.reflect.Method) {
      sb.append(typeDescriptor(((java.lang.reflect.Method) m).getReturnType()));
    } else {
      sb.append('V');
    }
    return sb.toString();
  }

  private static String typeDescriptor(Class<?> c) {
    if (c == void.class) return "V";
    if (c == boolean.class) return "Z";
    if (c == byte.class) return "B";
    if (c == char.class) return "C";
    if (c == short.class) return "S";
    if (c == int.class) return "I";
    if (c == long.class) return "J";
    if (c == float.class) return "F";
    if (c == double.class) return "D";
    if (c.isArray()) {
      return "[" + typeDescriptor(c.getComponentType());
    }
    return "L" + c.getName().replace('.', '/') + ";";
  }

  private static Class<?>[] resolveCandidates(Iterable<String> internalNames) {
    List<Class<?>> out = new ArrayList<>();
    for (String n : internalNames) {
      String cn = n.replace('/', '.');
      try {
        out.add(Class.forName(cn, false, ClassLoader.getSystemClassLoader()));
      } catch (Throwable ignored) {
        // class not present on this JVM
      }
    }
    return out.toArray(new Class<?>[0]);
  }

  private static byte[] rewrite(byte[] bytes, List<Spec> specs) throws IOException {
    List<ClassFileEditor.MethodTarget> targets = new ArrayList<>();
    for (Spec s : specs) {
      targets.add(new ClassFileEditor.MethodTarget(s.method, s.descriptor));
    }
    return ClassFileEditor.instrument(
        bytes,
        targets,
        (name, desc, access, maxStack, maxLocals, code, cp, out) -> {
          Spec match = null;
          for (Spec s : specs) {
            if (s.method.equals(name) && s.descriptor.equals(desc)) {
              match = s;
              break;
            }
          }
          if (match == null) return code;
          byte[] prologue = buildPrologue(cp, match.argLocal);
          byte[] result = new byte[prologue.length + code.length];
          System.arraycopy(prologue, 0, result, 0, prologue.length);
          System.arraycopy(code, 0, result, prologue.length, code.length);
          out.rewrote = true;
          out.newMaxStack = maxStack + 1;
          return result;
        });
  }

  private static byte[] buildPrologue(ConstantPoolBuilder cp, int argLocal) {
    int methodRef =
        cp.methodRef(
            "io/pjmartos/cull/agent/RecorderHolder",
            "recordResourceObject",
            "(Ljava/lang/Object;)V");
    byte[] out = new byte[4];
    switch (argLocal) {
      case 0:
        out[0] = 0x2A;
        break;
      case 1:
        out[0] = 0x2B;
        break;
      case 2:
        out[0] = 0x2C;
        break;
      case 3:
        out[0] = 0x2D;
        break;
      default:
        throw new IllegalArgumentException("argLocal " + argLocal);
    }
    out[1] = (byte) 0xB8;
    out[2] = (byte) ((methodRef >>> 8) & 0xFF);
    out[3] = (byte) (methodRef & 0xFF);
    return out;
  }

  private static Map<String, List<Spec>> buildSpecs() {
    Map<String, List<Spec>> map = new LinkedHashMap<>();
    add(map, "java/io/FileInputStream", "<init>", "(Ljava/io/File;)V", 1);
    add(map, "java/io/FileInputStream", "<init>", "(Ljava/lang/String;)V", 1);
    add(map, "java/io/FileReader", "<init>", "(Ljava/io/File;)V", 1);
    add(map, "java/io/FileReader", "<init>", "(Ljava/lang/String;)V", 1);
    add(
        map,
        "java/nio/file/Files",
        "newInputStream",
        "(Ljava/nio/file/Path;[Ljava/nio/file/OpenOption;)Ljava/io/InputStream;",
        0);
    add(
        map,
        "java/nio/file/Files",
        "newBufferedReader",
        "(Ljava/nio/file/Path;)Ljava/io/BufferedReader;",
        0);
    add(
        map,
        "java/nio/file/Files",
        "newBufferedReader",
        "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/io/BufferedReader;",
        0);
    add(map, "java/nio/file/Files", "readAllBytes", "(Ljava/nio/file/Path;)[B", 0);
    add(map, "java/nio/file/Files", "readString", "(Ljava/nio/file/Path;)Ljava/lang/String;", 0);
    add(
        map,
        "java/nio/file/Files",
        "readString",
        "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/lang/String;",
        0);
    add(map, "java/nio/file/Files", "lines", "(Ljava/nio/file/Path;)Ljava/util/stream/Stream;", 0);
    add(
        map,
        "java/nio/file/Files",
        "lines",
        "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/util/stream/Stream;",
        0);
    add(map, "java/nio/file/Files", "readAllLines", "(Ljava/nio/file/Path;)Ljava/util/List;", 0);
    add(
        map,
        "java/nio/file/Files",
        "readAllLines",
        "(Ljava/nio/file/Path;Ljava/nio/charset/Charset;)Ljava/util/List;",
        0);
    // ClassLoader and Class.getResource* methods record classpath resource reads.
    // Resources resolved against project class directories (testClassesDir /
    // mainClassesDir) are tracked as source-dependency edges. Resources inside
    // dependency JARs can't map to a local source file and are silently filtered
    // at commit time by CullSession.relativizeResource().
    add(
        map,
        "java/lang/ClassLoader",
        "getResourceAsStream",
        "(Ljava/lang/String;)Ljava/io/InputStream;",
        1);
    add(map, "java/lang/ClassLoader", "getResource", "(Ljava/lang/String;)Ljava/net/URL;", 1);
    add(
        map,
        "java/lang/ClassLoader",
        "getResources",
        "(Ljava/lang/String;)Ljava/util/Enumeration;",
        1);
    add(
        map,
        "java/lang/Class",
        "getResourceAsStream",
        "(Ljava/lang/String;)Ljava/io/InputStream;",
        1);
    add(map, "java/lang/Class", "getResource", "(Ljava/lang/String;)Ljava/net/URL;", 1);
    return map;
  }

  private static void add(
      Map<String, List<Spec>> map, String owner, String method, String desc, int argLocal) {
    map.computeIfAbsent(owner, k -> new ArrayList<>()).add(new Spec(method, desc, argLocal));
  }

  private static final class Spec {
    final String method;
    final String descriptor;
    final int argLocal;

    Spec(String method, String descriptor, int argLocal) {
      this.method = method;
      this.descriptor = descriptor;
      this.argLocal = argLocal;
    }
  }
}
