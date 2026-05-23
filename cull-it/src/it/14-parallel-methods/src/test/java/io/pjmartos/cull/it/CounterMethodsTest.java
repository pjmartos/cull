package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CounterMethodsTest {

  @Test
  void incrementsByOne() {
    assertEquals(2, new Counter().next(1));
  }

  @Test
  void incrementsByOneAgain() {
    assertEquals(3, new Counter().next(2));
  }

  @Test
  void incrementsByOneOnceMore() {
    assertEquals(4, new Counter().next(3));
  }
}
