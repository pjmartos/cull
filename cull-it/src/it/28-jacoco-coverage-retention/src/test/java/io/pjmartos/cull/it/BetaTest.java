package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BetaTest {
  @Test
  void exercisesBeta() {
    assertEquals(10, new Beta().value());
  }
}
