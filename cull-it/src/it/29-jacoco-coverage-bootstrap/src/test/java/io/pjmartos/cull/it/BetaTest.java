package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BetaTest {
  @Test
  void value() {
    assertEquals(2, new Beta().value());
  }
}
