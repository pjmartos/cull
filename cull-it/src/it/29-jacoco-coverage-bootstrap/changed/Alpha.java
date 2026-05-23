package io.pjmartos.cull.it;

public class Alpha {
  // Behaviour-preserving change: rehashes Alpha.java so the graph reselects
  // AlphaTest, while AlphaTest still passes.
  public int value() {
    return 3 - 2;
  }
}
