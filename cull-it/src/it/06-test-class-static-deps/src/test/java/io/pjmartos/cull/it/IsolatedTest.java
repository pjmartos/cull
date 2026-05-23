package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class IsolatedTest {

  @Test
  void noHelperReference() {
    assertEquals(7, new Widget().size());
  }
}
