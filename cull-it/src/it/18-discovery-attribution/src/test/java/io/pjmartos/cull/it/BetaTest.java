package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BetaTest {

  @Test
  void usesBetaOnly() {
    assertEquals("beta", new Beta().tag());
  }
}
