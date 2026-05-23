package io.pjmartos.cull.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

public final class TreeHasher {

  private TreeHasher() {}

  public static byte[] hashDirectory(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return new byte[32];
    }
    List<Path> files = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(root)) {
      walk.filter(Files::isRegularFile).forEach(files::add);
    }
    files.sort(Comparator.comparing(p -> root.relativize(p).toString().replace('\\', '/')));
    MessageDigest md = newDigest();
    for (Path p : files) {
      String rel = root.relativize(p).toString().replace('\\', '/');
      byte[] relBytes = rel.getBytes(StandardCharsets.UTF_8);
      md.update(relBytes);
      md.update((byte) 0);
      md.update(FileHasher.sha256(p));
    }
    return md.digest();
  }

  private static MessageDigest newDigest() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }
}
