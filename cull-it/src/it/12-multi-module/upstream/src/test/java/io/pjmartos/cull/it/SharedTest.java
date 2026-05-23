package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SharedTest {

  @Test
  void hasMagicNumber() {
    assertEquals(42, new Shared().magic());
  }
}
