package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CounterATest {

  @Test
  void incrementsByOne() {
    assertEquals(2, new Counter().next(1));
  }
}
