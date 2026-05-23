package io.pjmartos.cull.agent.listeners;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.agent.ObservationWriter;
import io.pjmartos.cull.agent.RecorderHolder;
import io.pjmartos.cull.agent.TestContext;
import io.pjmartos.cull.core.Observations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

class CullJunit4ListenerTest {

  @AfterEach
  void clearHolder() {
    RecorderHolder.install(null, null);
  }

  @Test
  void attributesClassLoadsToRunningJunit4TestClass(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("obs.bin");
    ObservationWriter writer = new ObservationWriter(obs);
    writer.writeForkMetadata("fork-junit4");
    RecorderHolder.install(new TestContext(), writer);

    CullJunit4Listener listener = new CullJunit4Listener();
    Description d = Description.createTestDescription("com.example.FooTest", "works");
    listener.testStarted(d);
    RecorderHolder.recordClassLoad("com/example/Helper");
    listener.testFinished(d);
    writer.closeQuietly();

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(obs)));
    assertTrue(deps.getOrDefault("com.example.FooTest", Set.of()).contains("com.example.Helper"));
  }

  @Test
  void nestedClassNameNormalizesToTopLevel(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("obs.bin");
    ObservationWriter writer = new ObservationWriter(obs);
    writer.writeForkMetadata("fork-nested");
    RecorderHolder.install(new TestContext(), writer);

    CullJunit4Listener listener = new CullJunit4Listener();
    Description d = Description.createTestDescription("com.example.OuterTest$Inner", "t");
    listener.testStarted(d);
    RecorderHolder.recordClassLoad("com/example/Dep");
    listener.testFinished(d);
    writer.closeQuietly();

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(obs)));
    assertTrue(deps.containsKey("com.example.OuterTest"));
    assertFalse(deps.containsKey("com.example.OuterTest$Inner"));
  }

  @Test
  void testFailureIsANoOpAndNoAgentIsTolerated() {
    RecorderHolder.install(null, null);
    CullJunit4Listener listener = new CullJunit4Listener();
    Description d = Description.createTestDescription("com.example.BarTest", "x");
    assertDoesNotThrow(
        () -> {
          listener.testStarted(d);
          listener.testFailure(new Failure(d, new RuntimeException("boom")));
          listener.testFinished(d);
        });
  }
}
