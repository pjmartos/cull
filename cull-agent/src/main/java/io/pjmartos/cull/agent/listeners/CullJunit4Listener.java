package io.pjmartos.cull.agent.listeners;

import io.pjmartos.cull.agent.RecorderHolder;
import org.junit.runner.Description;
import org.junit.runner.notification.Failure;
import org.junit.runner.notification.RunListener;

public final class CullJunit4Listener extends RunListener {

  @Override
  public void testStarted(Description description) {
    String cn = TestClassNames.topLevel(description.getClassName());
    if (cn == null) return;
    RecorderHolder.notifyTestStart(cn);
  }

  @Override
  public void testFinished(Description description) {
    String cn = TestClassNames.topLevel(description.getClassName());
    if (cn == null) return;
    RecorderHolder.notifyTestEnd(cn);
  }

  @Override
  public void testFailure(Failure failure) {
    // no-op; surefire xml is the source of truth for failures
  }
}
