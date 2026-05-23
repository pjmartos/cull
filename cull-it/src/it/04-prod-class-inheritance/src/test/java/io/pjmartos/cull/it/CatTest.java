package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CatTest {

  @Test
  void meows() {
    assertEquals("meow", new Cat().voice());
  }

  @Test
  void describesItself() {
    assertEquals("an animal that says meow", new Cat().describe());
  }
}
