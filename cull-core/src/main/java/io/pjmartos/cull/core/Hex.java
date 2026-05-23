package io.pjmartos.cull.core;

public final class Hex {

  private static final char[] DIGITS = "0123456789abcdef".toCharArray();

  private Hex() {}

  public static String encode(byte[] bytes) {
    char[] out = new char[bytes.length * 2];
    for (int i = 0; i < bytes.length; i++) {
      int v = bytes[i] & 0xFF;
      out[i * 2] = DIGITS[v >>> 4];
      out[i * 2 + 1] = DIGITS[v & 0xF];
    }
    return new String(out);
  }

  public static byte[] decode(String hex) {
    int len = hex.length();
    if ((len & 1) != 0) {
      throw new IllegalArgumentException("odd hex length");
    }
    byte[] out = new byte[len / 2];
    for (int i = 0; i < out.length; i++) {
      int hi = digit(hex.charAt(i * 2));
      int lo = digit(hex.charAt(i * 2 + 1));
      out[i] = (byte) ((hi << 4) | lo);
    }
    return out;
  }

  private static int digit(char c) {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return 10 + c - 'a';
    if (c >= 'A' && c <= 'F') return 10 + c - 'A';
    throw new IllegalArgumentException("invalid hex char: " + c);
  }
}
