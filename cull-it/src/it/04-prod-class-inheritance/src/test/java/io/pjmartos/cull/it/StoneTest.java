package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class StoneTest {

  @Test
  void rolls() {
    assertEquals("rolling", new Stone().roll());
  }
}
