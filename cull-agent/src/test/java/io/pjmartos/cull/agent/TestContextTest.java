package io.pjmartos.cull.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class TestContextTest {

  @Test
  void singleTestActive() {
    TestContext ctx = new TestContext();
    ctx.onTestStart("com.A");
    assertEquals(Set.of("com.A"), ctx.currentTests());
    assertTrue(ctx.discoveryComplete());
  }

  @Test
  void testEndRemovesFromActive() {
    TestContext ctx = new TestContext();
    ctx.onTestStart("com.A");
    ctx.onTestEnd("com.A");
    assertTrue(ctx.currentTests().isEmpty());
  }

  @Test
  void discoveryNotCompleteBeforeFirstTest() {
    TestContext ctx = new TestContext();
    assertFalse(ctx.discoveryComplete());
    assertTrue(ctx.currentTests().isEmpty());
  }

  @Test
  void nestedTestReturnsInnermost() {
    TestContext ctx = new TestContext();
    ctx.onTestStart("com.Outer");
    ctx.onTestStart("com.Inner");
    // currentTests returns the top of the stack
    assertEquals(Set.of("com.Inner"), ctx.currentTests());
  }

  @Test
  void classWindowActiveWhenNoMethodRunning() {
    TestContext ctx = new TestContext();
    ctx.onClassStart("com.C");
    assertEquals(Set.of("com.C"), ctx.currentTests());
    assertTrue(ctx.discoveryComplete());
  }

  @Test
  void methodInsideClassWindowThenWindowCloses() {
    TestContext ctx = new TestContext();
    ctx.onClassStart("com.C");
    ctx.onTestStart("com.C");
    assertEquals(Set.of("com.C"), ctx.currentTests());
    ctx.onTestEnd("com.C");
    assertEquals(Set.of("com.C"), ctx.currentTests(), "class window still open after method ends");
    ctx.onClassEnd("com.C");
    assertTrue(ctx.currentTests().isEmpty());
  }

  @Test
  void classWindowVisibleFromOtherThread() throws InterruptedException {
    TestContext ctx = new TestContext();
    ctx.onClassStart("com.C");
    java.util.concurrent.atomic.AtomicReference<Set<String>> seen =
        new java.util.concurrent.atomic.AtomicReference<>();
    Thread t = new Thread(() -> seen.set(ctx.currentTests()));
    t.start();
    t.join();
    assertEquals(
        Set.of("com.C"),
        seen.get(),
        "loads on a framework/async thread during a class window attribute to that class");
  }

  @Test
  void discoveryCompleteAfterClassStart() {
    TestContext ctx = new TestContext();
    assertFalse(ctx.discoveryComplete());
    ctx.onClassStart("com.C");
    assertTrue(ctx.discoveryComplete());
  }

  @Test
  void globalActiveTracksAllConcurrent() {
    TestContext ctx = new TestContext();
    ctx.onTestStart("com.A");
    ctx.onTestStart("com.B");
    // stack-based: innermost is B, but both are globally active
    assertEquals(Set.of("com.B"), ctx.currentTests());
    ctx.onTestEnd("com.B");
    assertEquals(Set.of("com.A"), ctx.currentTests());
  }
}
