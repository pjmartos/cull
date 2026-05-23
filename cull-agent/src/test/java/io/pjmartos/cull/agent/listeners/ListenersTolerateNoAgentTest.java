package io.pjmartos.cull.agent.listeners;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import io.pjmartos.cull.agent.RecorderHolder;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.platform.engine.TestDescriptor;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.UniqueId;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

class ListenersTolerateNoAgentTest {

  @BeforeEach
  void resetHolder() {
    RecorderHolder.install(null, null);
  }

  @AfterEach
  void clearHolder() {
    RecorderHolder.install(null, null);
  }

  @Test
  void junit5ListenerIsNoopWithoutAgent() {
    CullJunit5Listener l = new CullJunit5Listener();
    TestIdentifier id = TestIdentifier.from(new StubDescriptor("com.example.FakeTest"));
    assertDoesNotThrow(() -> l.executionStarted(id));
    assertDoesNotThrow(() -> l.executionFinished(id, null));
  }

  @Test
  void junit4ListenerIsNoopWithoutAgent() {
    CullJunit4Listener l = new CullJunit4Listener();
    Description d = Description.createTestDescription("com.example.FakeTest", "ok");
    assertDoesNotThrow(() -> l.testStarted(d));
    assertDoesNotThrow(() -> l.testFinished(d));
    assertDoesNotThrow(() -> l.testFailure(new Failure(d, new RuntimeException("boom"))));
  }

  @Test
  void recorderHolderRecordResourceObjectIsNoopWithoutAgent() {
    assertDoesNotThrow(() -> RecorderHolder.recordResourceObject("/some/path"));
    assertDoesNotThrow(() -> RecorderHolder.recordClassLoad("java/lang/String"));
    assertDoesNotThrow(() -> RecorderHolder.recordResource("/some/resource"));
  }

  private static final class StubDescriptor implements TestDescriptor {
    private final String fqcn;
    private final UniqueId id;

    StubDescriptor(String fqcn) {
      this.fqcn = fqcn;
      this.id = UniqueId.forEngine("stub").append("class", UUID.randomUUID().toString());
    }

    @Override
    public UniqueId getUniqueId() {
      return id;
    }

    @Override
    public String getDisplayName() {
      return fqcn;
    }

    @Override
    public java.util.Set<org.junit.platform.engine.TestTag> getTags() {
      return java.util.Set.of();
    }

    @Override
    public Optional<TestSource> getSource() {
      return Optional.of(ClassSource.from(fqcn));
    }

    @Override
    public Optional<TestDescriptor> getParent() {
      return Optional.empty();
    }

    @Override
    public void setParent(TestDescriptor parent) {}

    @Override
    public java.util.Set<? extends TestDescriptor> getChildren() {
      return java.util.Set.of();
    }

    @Override
    public void addChild(TestDescriptor descriptor) {}

    @Override
    public void removeChild(TestDescriptor descriptor) {}

    @Override
    public void removeFromHierarchy() {}

    @Override
    public Type getType() {
      return Type.TEST;
    }

    @Override
    public Optional<? extends TestDescriptor> findByUniqueId(UniqueId uniqueId) {
      return Optional.empty();
    }
  }
}
