package io.pjmartos.cull.agent;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TestContext {

  private final ThreadLocal<Deque<String>> methodStack = ThreadLocal.withInitial(ArrayDeque::new);
  private final ThreadLocal<Deque<String>> classStack = ThreadLocal.withInitial(ArrayDeque::new);
  private final Set<String> globalActiveMethods = ConcurrentHashMap.newKeySet();
  private final Set<String> globalActiveClasses = ConcurrentHashMap.newKeySet();
  private volatile boolean discoveryComplete;

  public void onClassStart(String testId) {
    classStack.get().push(testId);
    globalActiveClasses.add(testId);
    discoveryComplete = true;
  }

  public void onClassEnd(String testId) {
    Deque<String> s = classStack.get();
    s.remove(testId);
    // Cross-thread loads during the window resolve via globalActiveClasses;
    // dropping the entry once the (outer) window closes can only cause bounded
    // over-attribution, never a miss.
    globalActiveClasses.remove(testId);
  }

  public void onTestStart(String testId) {
    methodStack.get().push(testId);
    globalActiveMethods.add(testId);
    discoveryComplete = true;
  }

  public void onTestEnd(String testId) {
    methodStack.get().remove(testId);
    globalActiveMethods.remove(testId);
  }

  public Set<String> currentTests() {
    Deque<String> m = methodStack.get();
    if (!m.isEmpty()) {
      return Set.of(m.peek());
    }
    Deque<String> c = classStack.get();
    if (!c.isEmpty()) {
      return Set.of(c.peek());
    }
    if (!globalActiveMethods.isEmpty()) {
      return Set.copyOf(globalActiveMethods);
    }
    return globalActiveClasses.isEmpty() ? Set.of() : Set.copyOf(globalActiveClasses);
  }

  public boolean discoveryComplete() {
    return discoveryComplete;
  }
}
