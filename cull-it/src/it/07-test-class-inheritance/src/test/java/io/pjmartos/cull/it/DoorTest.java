package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DoorTest extends BaseSupport {

  @Test
  void doorIsClosed() {
    assertEquals(shouldBeOpen(), new Door().isOpen());
  }
}
