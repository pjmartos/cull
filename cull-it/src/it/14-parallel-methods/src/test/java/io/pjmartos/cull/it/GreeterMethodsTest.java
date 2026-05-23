package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class GreeterMethodsTest {

  @Test
  void greetsAlice() {
    assertEquals("hello alice", new Greeter().greet("alice"));
  }

  @Test
  void greetsBob() {
    assertEquals("hello bob", new Greeter().greet("bob"));
  }
}
