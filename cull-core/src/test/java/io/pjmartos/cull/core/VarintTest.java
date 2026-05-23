package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class VarintTest {

  @Test
  void roundTripSmall() throws IOException {
    long[] values = {0, 1, 127, 128, 255, 16383, 16384, Integer.MAX_VALUE, Long.MAX_VALUE};
    for (long v : values) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      Varint.writeUnsigned(out, v);
      long round = Varint.readUnsigned(new ByteArrayInputStream(out.toByteArray()));
      assertEquals(v, round, "value " + v);
    }
  }

  @Test
  void rejectsNegative() {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    assertThrows(IllegalArgumentException.class, () -> Varint.writeUnsigned(out, -1));
  }

  @Test
  void readUnsignedIntRejectsOverflow() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    Varint.writeUnsigned(out, ((long) Integer.MAX_VALUE) + 1L);
    assertThrows(
        IOException.class,
        () -> Varint.readUnsignedInt(new ByteArrayInputStream(out.toByteArray())));
  }

  @Test
  void truncatedStream() {
    byte[] truncated = {(byte) 0x80};
    assertThrows(
        EOFException.class, () -> Varint.readUnsigned(new ByteArrayInputStream(truncated)));
  }
}
