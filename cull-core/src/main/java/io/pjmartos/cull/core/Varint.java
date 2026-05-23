package io.pjmartos.cull.core;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class Varint {

  private Varint() {}

  public static void writeUnsigned(OutputStream out, long value) throws IOException {
    if (value < 0) {
      throw new IllegalArgumentException("negative varint: " + value);
    }
    while ((value & ~0x7FL) != 0) {
      out.write((int) ((value & 0x7FL) | 0x80L));
      value >>>= 7;
    }
    out.write((int) value);
  }

  public static long readUnsigned(InputStream in) throws IOException {
    long result = 0;
    int shift = 0;
    while (true) {
      int b = in.read();
      if (b < 0) {
        throw new EOFException("varint truncated");
      }
      result |= ((long) (b & 0x7F)) << shift;
      if ((b & 0x80) == 0) {
        return result;
      }
      shift += 7;
      if (shift > 63) {
        throw new IOException("varint overflow");
      }
    }
  }

  public static int readUnsignedInt(InputStream in) throws IOException {
    long v = readUnsigned(in);
    if (v > Integer.MAX_VALUE) {
      throw new IOException("varint exceeds int: " + v);
    }
    return (int) v;
  }
}
