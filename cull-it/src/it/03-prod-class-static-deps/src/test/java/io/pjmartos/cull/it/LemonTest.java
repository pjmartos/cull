package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LemonTest {

  @Test
  void exercisesLemon() {
    assertEquals("juice", new Lemon().squeeze());
  }
}
