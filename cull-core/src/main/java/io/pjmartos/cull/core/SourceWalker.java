package io.pjmartos.cull.core;

import java.io.IOException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class SourceWalker {

  private SourceWalker() {}

  public static List<Path> listFiles(Path root) throws IOException {
    if (!Files.isDirectory(root)) {
      return List.of();
    }
    List<Path> out = new ArrayList<>();
    Set<Path> visitedRealDirs = new HashSet<>();
    Files.walkFileTree(
        root,
        EnumSet.of(FileVisitOption.FOLLOW_LINKS),
        Integer.MAX_VALUE,
        new FileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
            Path real = realPathQuiet(dir);
            if (real != null && !visitedRealDirs.add(real)) {
              return FileVisitResult.SKIP_SUBTREE;
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (attrs.isRegularFile()) {
              out.add(file);
            }
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFileFailed(Path file, IOException exc) {
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
            return FileVisitResult.CONTINUE;
          }
        });
    return out;
  }

  private static Path realPathQuiet(Path dir) {
    try {
      return dir.toRealPath(LinkOption.NOFOLLOW_LINKS);
    } catch (IOException e) {
      try {
        return dir.toAbsolutePath().normalize();
      } catch (RuntimeException re) {
        return null;
      }
    }
  }
}
