package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FooTest {

  @Test
  void deliberatelyFails() {
    assertEquals(999, new Foo().x(), "scenario 17 expects this assertion to fail");
  }
}
