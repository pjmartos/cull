package io.pjmartos.cull.agent.bytecode;

@SuppressWarnings("unused")
public class BranchedSample {

  public int max(int a, int b) {
    return Math.max(a, b);
  }

  public String describe(int n) {
    if (n < 0) {
      return "negative";
    } else if (n == 0) {
      return "zero";
    } else {
      return "positive";
    }
  }

  public int sumLoop(int n) {
    int s = 0;
    for (int i = 0; i < n; i++) {
      s += i;
    }
    return s;
  }

  public Object newObjectAndAssert(boolean cond) {
    Object o = new Object();
    if (cond) {
      return o;
    }
    return null;
  }
}
