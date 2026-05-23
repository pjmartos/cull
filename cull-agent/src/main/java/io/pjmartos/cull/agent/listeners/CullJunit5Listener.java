package io.pjmartos.cull.agent.listeners;

import io.pjmartos.cull.agent.RecorderHolder;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.TestSource;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

public final class CullJunit5Listener implements TestExecutionListener {

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    // discovery phase finished by the time the plan is executed
  }

  // A class-backed container (test class, @Nested, @TestTemplate/@TestFactory)
  // opens a class window so loads during @BeforeAll / context bootstrap /
  // static init are attributed to that class instead of leaking out as
  // discovery/orphan and being fanned to every test in the fork. Engine/plan
  // containers have no class source, so classNameOf is null and they stay
  // (correctly) outside any window.
  @Override
  public void executionStarted(TestIdentifier id) {
    String cn = classNameOf(id);
    if (cn == null) return;
    if (id.isTest()) {
      RecorderHolder.notifyTestStart(cn);
    } else {
      RecorderHolder.notifyClassStart(cn);
    }
  }

  @Override
  public void executionFinished(TestIdentifier id, TestExecutionResult result) {
    String cn = classNameOf(id);
    if (cn == null) return;
    if (id.isTest()) {
      RecorderHolder.notifyTestEnd(cn);
    } else {
      RecorderHolder.notifyClassEnd(cn);
    }
  }

  private static String classNameOf(TestIdentifier id) {
    TestSource src = id.getSource().orElse(null);
    String name = null;
    if (src instanceof ClassSource) {
      name = ((ClassSource) src).getClassName();
    } else if (src instanceof MethodSource) {
      name = ((MethodSource) src).getClassName();
    }
    return TestClassNames.topLevel(name);
  }
}
