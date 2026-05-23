package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HexTest {

  @Test
  void roundTrip() {
    byte[] data = {0, (byte) 0xFF, 0x10, 0x20, (byte) 0xAB, (byte) 0xCD};
    String hex = Hex.encode(data);
    assertEquals("00ff1020abcd", hex);
    assertArrayEquals(data, Hex.decode(hex));
  }
}
