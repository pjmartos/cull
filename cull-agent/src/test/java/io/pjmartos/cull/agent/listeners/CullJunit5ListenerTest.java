package io.pjmartos.cull.agent.listeners;

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
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.AbstractTestDescriptor;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestIdentifier;

class CullJunit5ListenerTest {

  @Test
  void classContainerOpensWindowScopingSetupLoadsAndDroppingDiscovery(@TempDir Path tmp)
      throws IOException {
    Path obs = tmp.resolve("obs.bin");
    ObservationWriter writer = new ObservationWriter(obs);
    writer.writeForkMetadata("fork-j5");
    TestContext ctx = new TestContext();
    RecorderHolder.install(ctx, writer);

    CullJunit5Listener listener = new CullJunit5Listener();

    // JUnit Platform discovery loads candidate test classes before execution.
    RecorderHolder.recordClassLoad("com/example/OtherTest");

    TestIdentifier clazz = classContainer("com.example.SpringTest");
    TestIdentifier method = methodTest("com.example.SpringTest", "loadsContext");

    listener.executionStarted(clazz);
    // @BeforeAll / Spring context bootstrap happens before any test method.
    RecorderHolder.recordClassLoad("com/example/ContextBean");
    listener.executionStarted(method);
    RecorderHolder.recordClassLoad("com/example/UsedInTest");
    listener.executionFinished(method, TestExecutionResult.successful());
    listener.executionFinished(clazz, TestExecutionResult.successful());
    writer.closeQuietly();

    Observations.Fork fork = Observations.readFile(obs);
    Map<String, Set<String>> deps = Observations.mergeRuntimeClassDeps(List.of(fork));
    Set<String> springDeps = deps.get("com.example.SpringTest");
    assertTrue(springDeps.contains("com.example.ContextBean"), "@BeforeAll/context load scoped");
    assertTrue(springDeps.contains("com.example.UsedInTest"), "in-test load scoped");
    assertFalse(
        springDeps.contains("com.example.OtherTest"),
        "discovery-phase load dropped once the fork uses class windows");
  }

  private static TestIdentifier classContainer(String className) {
    UniqueId id = UniqueId.forEngine("junit-jupiter").append("class", className);
    final TestSource source = ClassSource.from(className);
    return TestIdentifier.from(
        new AbstractTestDescriptor(id, className) {
          @Override
          public Type getType() {
            return Type.CONTAINER;
          }

          @Override
          public Optional<TestSource> getSource() {
            return Optional.of(source);
          }
        });
  }

  private static TestIdentifier methodTest(String className, String methodName) {
    UniqueId id =
        UniqueId.forEngine("junit-jupiter")
            .append("class", className)
            .append("method", methodName + "()");
    final TestSource source = MethodSource.from(className, methodName, "");
    return TestIdentifier.from(
        new AbstractTestDescriptor(id, methodName + "()") {
          @Override
          public Type getType() {
            return Type.TEST;
          }

          @Override
          public Optional<TestSource> getSource() {
            return Optional.of(source);
          }
        });
  }
}
