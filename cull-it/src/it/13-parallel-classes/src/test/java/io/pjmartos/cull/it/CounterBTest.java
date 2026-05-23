package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CounterBTest {

  @Test
  void wrapsWithBrackets() {
    assertEquals("[ok]", new Stamper().stamp("ok"));
  }
}
