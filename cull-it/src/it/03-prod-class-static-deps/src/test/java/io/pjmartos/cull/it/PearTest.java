package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PearTest {

  @Test
  void exercisesPear() {
    assertEquals(6, new Pear().helper(3));
  }
}
