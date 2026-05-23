package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertFalse;

import org.junit.jupiter.api.Test;

class StandaloneTest {

  @Test
  void doesNotInheritBase() {
    assertFalse(new Door().isOpen());
  }
}
