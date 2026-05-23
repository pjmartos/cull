package io.pjmartos.cull.agent.listeners;

import io.pjmartos.cull.agent.RecorderHolder;
import org.testng.ITestContext;
import org.testng.ITestListener;
import org.testng.ITestResult;

public final class CullTestngListener implements ITestListener {

  @Override
  public void onTestStart(ITestResult result) {
    String cn = TestClassNames.topLevel(result.getTestClass().getName());
    if (cn == null) return;
    RecorderHolder.notifyTestStart(cn);
  }

  @Override
  public void onTestSuccess(ITestResult result) {
    end(result);
  }

  @Override
  public void onTestFailure(ITestResult result) {
    end(result);
  }

  @Override
  public void onTestSkipped(ITestResult result) {
    end(result);
  }

  @Override
  public void onTestFailedButWithinSuccessPercentage(ITestResult result) {
    end(result);
  }

  @Override
  public void onStart(ITestContext context) {
    // discovery already done at the framework level
  }

  @Override
  public void onFinish(ITestContext context) {
    // no-op
  }

  private void end(ITestResult result) {
    String cn = TestClassNames.topLevel(result.getTestClass().getName());
    if (cn == null) return;
    RecorderHolder.notifyTestEnd(cn);
  }
}
