package io.pjmartos.cull.it;

public final class Alpha {
  public int value() {
    // Changed body: recompiles to a new class id and rehashes, so cull
    // reselects AlphaTest only — BetaTest is not run this round.
    return 2;
  }
}
