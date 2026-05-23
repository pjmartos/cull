package io.pjmartos.cull.agent.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.agent.ObservationWriter;
import io.pjmartos.cull.agent.RecorderHolder;
import io.pjmartos.cull.agent.TestContext;
import io.pjmartos.cull.core.Observations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

class NestedAttributionTest {

  @Test
  void nestedClassesNormalizeToOuter(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("obs.bin");
    ObservationWriter writer = new ObservationWriter(obs);
    writer.writeForkMetadata("fork-nested");
    TestContext ctx = new TestContext();
    RecorderHolder.install(ctx, writer);

    CullJunit5Listener listener = new CullJunit5Listener();
    TestIdentifier nestedId = makeMethodTestId("com.example.OuterTest$NestedCases", "doThing");
    listener.executionStarted(nestedId);
    RecorderHolder.recordClassLoad("com/example/Helper");
    listener.executionFinished(nestedId, TestExecutionResult.successful());
    writer.closeQuietly();

    Observations.Fork fork = Observations.readFile(obs);
    Map<String, Set<String>> deps = Observations.mergeRuntimeClassDeps(List.of(fork));
    assertTrue(deps.containsKey("com.example.OuterTest"));
    assertTrue(deps.get("com.example.OuterTest").contains("com.example.Helper"));
    assertEquals(1, deps.size());
  }

  private static TestIdentifier makeMethodTestId(String className, String methodName) {
    UniqueId id =
        UniqueId.forEngine("junit-jupiter")
            .append("class", className)
            .append("method", methodName + "()");
    final TestSource source = MethodSource.from(className, methodName, "");
    AbstractTestDescriptor td =
        new AbstractTestDescriptor(id, methodName + "()") {
          @Override
          public Type getType() {
            return Type.TEST;
          }

          @Override
          public Optional<TestSource> getSource() {
            return Optional.of(source);
          }
        };
    return TestIdentifier.from(td);
  }
}
