package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AlphaTest {

  @Test
  void measuresVolume() {
    assertEquals(8, new Box().volume());
  }

  @Test
  void newlyAddedAssertion() {
    assertEquals(8, new Box().volume());
  }
}
