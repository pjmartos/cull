package io.pjmartos.cull.it;

/** Downstream class with no dependency on the upstream module. */
public class Standalone {

  public int answer() {
    return 7;
  }
}
