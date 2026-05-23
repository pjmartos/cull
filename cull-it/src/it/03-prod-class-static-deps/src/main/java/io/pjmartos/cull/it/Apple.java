package io.pjmartos.cull.it;

public class Apple {

  private final Pear pear = new Pear();

  public int compute(int n) {
    return pear.helper(n) + 1;
  }
}
