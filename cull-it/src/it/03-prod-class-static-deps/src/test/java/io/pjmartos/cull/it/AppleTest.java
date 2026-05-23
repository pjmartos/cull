package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AppleTest {

  @Test
  void exercisesApple() {
    assertEquals(7, new Apple().compute(3));
  }
}
