package io.pjmartos.cull.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public final class FileHasher {

  private static final int BUFFER = 64 * 1024;

  private FileHasher() {}

  public static byte[] sha256(Path p) throws IOException {
    MessageDigest md = newDigest();
    byte[] buf = new byte[BUFFER];
    try (InputStream in = Files.newInputStream(p)) {
      int n;
      while ((n = in.read(buf)) != -1) {
        md.update(buf, 0, n);
      }
    }
    return md.digest();
  }

  public static byte[] sha256(byte[] data) {
    return newDigest().digest(data);
  }

  public static Map<Path, byte[]> hashAll(Collection<Path> paths, ExecutorService pool) {
    Map<Path, Future<byte[]>> futures = new HashMap<>();
    for (Path p : paths) {
      futures.put(p, pool.submit(() -> sha256(p)));
    }
    Map<Path, byte[]> result = new ConcurrentHashMap<>();
    for (Map.Entry<Path, Future<byte[]>> e : futures.entrySet()) {
      try {
        result.put(e.getKey(), e.getValue().get());
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted hashing " + e.getKey(), ie);
      } catch (ExecutionException ex) {
        throw new IllegalStateException("failed hashing " + e.getKey(), ex);
      }
    }
    return result;
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
