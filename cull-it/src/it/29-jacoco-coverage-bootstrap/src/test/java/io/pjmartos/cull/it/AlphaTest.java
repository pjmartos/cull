package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AlphaTest {
  @Test
  void value() {
    assertEquals(1, new Alpha().value());
  }
}
