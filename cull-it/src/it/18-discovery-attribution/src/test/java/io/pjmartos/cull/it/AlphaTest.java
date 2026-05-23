package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AlphaTest {

  @Test
  void usesAlphaOnly() {
    assertEquals("alpha", new Alpha().tag());
  }
}
