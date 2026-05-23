package io.pjmartos.cull.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public final class Merkle {

  private Merkle() {}

  public static byte[] hashGroup(List<byte[]> tuples) {
    MessageDigest md = newDigest();
    ByteArrayOutputStream lengthPrefix = new ByteArrayOutputStream(8);
    for (byte[] tuple : tuples) {
      lengthPrefix.reset();
      try {
        Varint.writeUnsigned(lengthPrefix, tuple.length);
      } catch (IOException e) {
        throw new IllegalStateException(e);
      }
      md.update(lengthPrefix.toByteArray());
      md.update(tuple);
    }
    return md.digest();
  }

  public static byte[] sha256(byte[]... parts) {
    MessageDigest md = newDigest();
    for (byte[] p : parts) {
      md.update(p);
    }
    return md.digest();
  }

  public static byte[] tuple(byte[]... parts) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (byte[] p : parts) {
      Varint.writeUnsigned(out, p == null ? 0 : p.length);
      if (p != null) {
        out.write(p);
      }
    }
    return out.toByteArray();
  }

  public static byte[] tupleFromStrings(String... s) throws IOException {
    byte[][] parts = new byte[s.length][];
    for (int i = 0; i < s.length; i++) {
      parts[i] =
          s[i] == null ? new byte[0] : s[i].getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    return tuple(parts);
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
