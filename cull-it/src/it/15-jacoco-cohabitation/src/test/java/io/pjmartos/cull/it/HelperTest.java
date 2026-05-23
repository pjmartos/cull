package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HelperTest {

  @Test
  void triples() {
    assertEquals(9, new Helper().triple(3));
  }
}
