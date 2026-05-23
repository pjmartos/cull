package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BetaTest {

  @Test
  void independentCheck() {
    assertEquals(8, new Box().volume());
  }
}
