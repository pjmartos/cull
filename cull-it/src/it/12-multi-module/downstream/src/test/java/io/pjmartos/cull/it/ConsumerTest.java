package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ConsumerTest {

  @Test
  void doublesSharedMagic() {
    assertEquals(84, new Consumer().doubled());
  }
}
