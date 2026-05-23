package io.pjmartos.cull.agent;

import java.util.Set;

public final class RecorderHolder {

  private static volatile TestContext context;
  private static volatile ObservationWriter writer;

  private RecorderHolder() {}

  public static void install(TestContext ctx, ObservationWriter w) {
    context = ctx;
    writer = w;
  }

  public static void notifyTestStart(String className) {
    if (className == null) return;
    TestContext c = context;
    ObservationWriter w = writer;
    if (c == null || w == null) return;
    if (!c.discoveryComplete()) {
      w.recordDiscoveryEnd();
    }
    c.onTestStart(className);
    w.recordTestStart(className);
  }

  public static void notifyClassStart(String className) {
    if (className == null) return;
    TestContext c = context;
    ObservationWriter w = writer;
    if (c == null || w == null) return;
    if (!c.discoveryComplete()) {
      w.recordDiscoveryEnd();
    }
    c.onClassStart(className);
    w.recordClassStart(className);
  }

  public static void notifyClassEnd(String className) {
    if (className == null) return;
    TestContext c = context;
    ObservationWriter w = writer;
    if (c != null) {
      c.onClassEnd(className);
    }
    if (w != null) {
      w.recordClassEnd(className);
    }
  }

  public static void notifyTestEnd(String className) {
    if (className == null) return;
    TestContext c = context;
    ObservationWriter w = writer;
    if (c != null) {
      c.onTestEnd(className);
    }
    if (w != null) {
      w.recordTestEnd(className);
    }
  }

  public static TestContext context() {
    return context;
  }

  public static ObservationWriter writer() {
    return writer;
  }

  // null returns distinguish "agent not bound" from "no active tests" (empty set).
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  private static Set<String> currentTests() {
    TestContext c = context;
    if (c == null) return null;
    return c.currentTests();
  }

  private static boolean discoveryComplete() {
    TestContext c = context;
    if (c == null) return false;
    return c.discoveryComplete();
  }

  public static void recordClassLoad(String name) {
    if (name == null) return;
    // The JVM ClassFileTransformer passes class names in internal form
    // (slash-separated, e.g. "io/pjmartos/cull/it/AlphaTest"). Convert to
    // dotted form ("io.pjmartos.cull.it.AlphaTest") to match the format
    // used by classNameToPath in CullSession.buildClassToPathIndex().
    String dotted = name.replace('/', '.');
    Set<String> active = currentTests();
    if (active == null) return;
    ObservationWriter w = writer;
    if (w == null) return;
    if (active.isEmpty()) {
      if (discoveryComplete()) {
        w.recordOrphanLoad(dotted);
      } else {
        w.recordDiscoveryClass(dotted);
      }
      return;
    }
    for (String t : active) {
      w.recordClassLoad(t, dotted);
    }
  }

  public static void recordResource(String path) {
    if (path == null) return;
    // Reading a .class file via ClassLoader.getResource(AsStream) is classpath
    // introspection (Spring's @ComponentScan, JUnit's discovery, JPA's @Entity
    // scan) — bytes get inspected for annotations, not the class behaviorally
    // depended on. The actual behavioral dep is captured the first time the
    // JVM defines the class, by RecordingTransformer. Recording the resource
    // read here on top would lump every scanned .class into the active test's
    // deps, making any unrelated test that triggers a classpath scan re-run
    // when an unrelated class changes.
    if (isClassFileResource(path)) return;
    Set<String> active = currentTests();
    if (active == null) return;
    ObservationWriter w = writer;
    if (w == null) return;
    if (active.isEmpty()) {
      w.recordOrphanResource(path);
      return;
    }
    for (String t : active) {
      w.recordResourceRead(t, path);
    }
  }

  private static boolean isClassFileResource(String path) {
    if (path.endsWith(".class")) return true;
    // URL forms: "file:/.../Foo.class", "jar:file:/.../!/.../Foo.class".
    // Anchor on suffix to avoid matching e.g. ".class.txt" or query strings.
    int q = path.indexOf('?');
    int hash = path.indexOf('#');
    int end = path.length();
    if (q >= 0) end = Math.min(end, q);
    if (hash >= 0) end = Math.min(end, hash);
    return end >= 6 && path.regionMatches(end - 6, ".class", 0, 6);
  }

  public static void recordResourceObject(Object o) {
    if (o == null) return;
    try {
      recordResource(String.valueOf(o));
    } catch (Throwable ignored) {
      // never propagate from instrumented JDK calls
    }
  }
}
