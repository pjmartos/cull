package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DogTest {

  @Test
  void barks() {
    assertEquals("woof", new Dog().voice());
  }

  @Test
  void describesItself() {
    assertEquals("an animal that says woof", new Dog().describe());
  }
}
