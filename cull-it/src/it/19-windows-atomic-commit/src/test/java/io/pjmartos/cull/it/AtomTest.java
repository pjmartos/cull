package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AtomTest {

  @Test
  void hasOneProton() {
    assertEquals(1, new Atom().proton());
  }
}
