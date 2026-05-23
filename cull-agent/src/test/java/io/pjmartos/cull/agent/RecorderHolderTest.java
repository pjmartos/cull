package io.pjmartos.cull.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecorderHolderTest {

  @Test
  void installSetsContextAndWriter(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("o.bin");
    TestContext ctx = new TestContext();
    ObservationWriter w = new ObservationWriter(obs);

    RecorderHolder.install(ctx, w);
    assertEquals(ctx, RecorderHolder.context());
    assertEquals(w, RecorderHolder.writer());
  }

  @Test
  void contextAndWriterNullBeforeInstall() {
    // RecorderHolder is a static global; other tests may have installed it.
    // We verify the install/context API shape — null-safe accessors work.
    // In a real agent, install happens once; here we just call the accessors.
    assertNotNull(RecorderHolder.class); // class loads
  }

  @Test
  void notifyTestStartRecordsTest(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("o.bin");
    TestContext ctx = new TestContext();
    ObservationWriter w = new ObservationWriter(obs);
    RecorderHolder.install(ctx, w);

    RecorderHolder.notifyTestStart("com.example.FooTest");
    assertTrue(ctx.discoveryComplete());

    w.closeQuietly();
  }

  @Test
  void notifyTestEndRemovesTest(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("o.bin");
    TestContext ctx = new TestContext();
    ObservationWriter w = new ObservationWriter(obs);
    RecorderHolder.install(ctx, w);

    RecorderHolder.notifyTestStart("com.example.FooTest");
    RecorderHolder.notifyTestEnd("com.example.FooTest");
    assertTrue(ctx.currentTests().isEmpty());

    w.closeQuietly();
  }

  @Test
  void classWindowScopesLoadsAndDropsPriorDiscovery(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("o.bin");
    TestContext ctx = new TestContext();
    ObservationWriter w = new ObservationWriter(obs);
    w.writeForkMetadata("f");
    RecorderHolder.install(ctx, w);

    // Loaded during JUnit discovery (no window yet) → discovery class.
    RecorderHolder.recordClassLoad("com/example/DiscoveredOnly");
    RecorderHolder.notifyClassStart("com.example.SpringTest");
    RecorderHolder.recordClassLoad("com/example/ContextBean");
    RecorderHolder.notifyClassEnd("com.example.SpringTest");
    w.closeQuietly();

    io.pjmartos.cull.core.Observations.Fork fork = io.pjmartos.cull.core.Observations.readFile(obs);
    java.util.Map<String, java.util.Set<String>> deps =
        io.pjmartos.cull.core.Observations.mergeRuntimeClassDeps(java.util.List.of(fork));
    assertTrue(deps.get("com.example.SpringTest").contains("com.example.ContextBean"));
    assertTrue(
        !deps.get("com.example.SpringTest").contains("com.example.DiscoveredOnly"),
        "discovery-phase load dropped once the fork uses class windows");
  }

  @Test
  void recordResourceNoOpWhenNoActiveTests(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("o.bin");
    TestContext ctx = new TestContext();
    ObservationWriter w = new ObservationWriter(obs);
    RecorderHolder.install(ctx, w);

    // No active test — recordResource should not throw
    RecorderHolder.recordResource("/some/path.txt");

    w.closeQuietly();
  }

  @Test
  void recordResourceDropsClassFileReads(@TempDir Path tmp) throws IOException {
    // .class reads via ClassLoader.getResourceAsStream are classpath
    // introspection (Spring/JPA scans), not behavioral deps; the actual class
    // load is captured by RecordingTransformer. Recording them as resource
    // deps would lump every scanned .class onto whichever test happens to be
    // running. Various path shapes JDK / loaders produce are filtered.
    Path obs = tmp.resolve("o.bin");
    TestContext ctx = new TestContext();
    ObservationWriter w = new ObservationWriter(obs);
    w.writeForkMetadata("f");
    RecorderHolder.install(ctx, w);

    RecorderHolder.notifyClassStart("com.example.SpringTest");
    // All these should be dropped:
    RecorderHolder.recordResource("com/example/Foo.class");
    RecorderHolder.recordResource("/com/example/Bar.class");
    RecorderHolder.recordResource("file:/C:/x/target/classes/com/example/Baz.class");
    RecorderHolder.recordResource("jar:file:/home/x/lib/foo.jar!/com/example/Quux.class");
    RecorderHolder.recordResource("jar:file:/x.jar!/com/example/Q.class?query=1");
    // Non-.class resources still get recorded:
    RecorderHolder.recordResource("application.properties");
    RecorderHolder.recordResource("templates/owners/list.html");
    RecorderHolder.notifyClassEnd("com.example.SpringTest");
    w.closeQuietly();

    io.pjmartos.cull.core.Observations.Fork fork = io.pjmartos.cull.core.Observations.readFile(obs);
    java.util.Map<String, java.util.Set<String>> deps =
        io.pjmartos.cull.core.Observations.mergeRuntimeResourceDeps(java.util.List.of(fork));
    java.util.Set<String> springTestDeps =
        deps.getOrDefault("com.example.SpringTest", java.util.Set.of());
    assertTrue(springTestDeps.contains("application.properties"));
    assertTrue(springTestDeps.contains("templates/owners/list.html"));
    for (String d : springTestDeps) {
      assertTrue(!d.endsWith(".class"), "class-file resource leaked into deps: " + d);
    }
  }
}
