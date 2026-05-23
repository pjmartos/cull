package io.pjmartos.cull.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.core.Observations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingTransformerTest {

  @AfterEach
  void clearHolder() {
    RecorderHolder.install(null, null);
  }

  @Test
  void transformNeverRewritesAndAlwaysReturnsNull() {
    RecordingTransformer xf = new RecordingTransformer();
    byte[] buf = {1, 2, 3};
    assertNull(xf.transform(null, "com/example/Foo", null, null, buf));
    assertNull(xf.transform(null, null, null, null, buf));
    assertNull(
        xf.transform(
            ClassLoader.getSystemClassLoader(),
            "io/pjmartos/cull/agent/IoHooks",
            String.class,
            null,
            buf));
  }

  @Test
  void recordsUserClassesButFiltersCullInternalAndNull(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("obs.bin");
    ObservationWriter writer = new ObservationWriter(obs);
    writer.writeForkMetadata("fork-xf");
    TestContext ctx = new TestContext();
    ctx.onTestStart("com.example.MyTest");
    RecorderHolder.install(ctx, writer);

    RecordingTransformer xf = new RecordingTransformer();
    xf.transform(null, "com/example/Service", null, null, new byte[0]);
    xf.transform(null, "io/pjmartos/cull/agent/RecorderHolder", null, null, new byte[0]);
    xf.transform(null, "io/pjmartos/cull/core/Hex", null, null, new byte[0]);
    xf.transform(null, "io/pjmartos/cull/agent/shaded/Foo", null, null, new byte[0]);
    xf.transform(null, null, null, null, new byte[0]);
    writer.closeQuietly();

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(obs)));
    Set<String> myDeps = deps.getOrDefault("com.example.MyTest", Set.of());
    assertTrue(myDeps.contains("com.example.Service"), "user classes are recorded; got: " + myDeps);
    assertFalse(
        myDeps.stream().anyMatch(d -> d.startsWith("io.pjmartos.cull.")),
        "cull-internal classes must be filtered; got: " + myDeps);
  }

  @Test
  void toleratesNoAgentBound() {
    RecorderHolder.install(null, null);
    RecordingTransformer xf = new RecordingTransformer();
    assertDoesNotThrow(() -> xf.transform(null, "com/example/Foo", null, null, new byte[0]));
  }
}
