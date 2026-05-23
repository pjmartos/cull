package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests for {@link SourceWalker}, including symlink handling and edge-case tree shapes. */
class SourceWalkerTest {

  @Test
  void emptyForNonexistentDirectory(@TempDir Path tmp) throws IOException {
    List<Path> files = SourceWalker.listFiles(tmp.resolve("does-not-exist"));
    assertEquals(0, files.size());
  }

  @Test
  void listsRegularFilesRecursively(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("a.txt"), "1");
    Path sub = Files.createDirectories(tmp.resolve("sub"));
    Files.writeString(sub.resolve("b.txt"), "2");
    Files.writeString(sub.resolve("c.txt"), "3");
    List<Path> files = SourceWalker.listFiles(tmp);
    assertEquals(3, files.size());
  }

  @Test
  void detectsAndPrunesSymlinkCycle(@TempDir Path tmp) throws IOException {
    Path a = Files.createDirectories(tmp.resolve("a"));
    Files.writeString(a.resolve("file.txt"), "real");
    Path link = a.resolve("loop");
    try {
      Files.createSymbolicLink(link, a);
    } catch (UnsupportedOperationException | IOException e) {
      assumeTrue(false, "filesystem/OS does not allow symlink creation: " + e.getMessage());
    }
    List<Path> files = SourceWalker.listFiles(tmp);
    long fileCount =
        files.stream().filter(p -> p.getFileName().toString().equals("file.txt")).count();
    assertTrue(
        fileCount >= 1 && fileCount <= 2,
        "must terminate and not blow up; saw " + fileCount + " file.txt entries");
  }

  @Test
  void followsHarmlessSymlinkToSiblingTree(@TempDir Path tmp) throws IOException {
    Path real = Files.createDirectories(tmp.resolve("real"));
    Files.writeString(real.resolve("inside.txt"), "x");
    Path linkParent = Files.createDirectories(tmp.resolve("via-link"));
    Path link = linkParent.resolve("alias");
    try {
      Files.createSymbolicLink(link, real);
    } catch (UnsupportedOperationException | IOException e) {
      assumeTrue(false, "filesystem/OS does not allow symlink creation: " + e.getMessage());
    }
    List<Path> files = SourceWalker.listFiles(linkParent);
    long match =
        files.stream().filter(p -> p.getFileName().toString().equals("inside.txt")).count();
    assertEquals(1L, match);
  }

  @Test
  void regularFileAsRootYieldsEmptyList(@TempDir Path tmp) throws IOException {
    Path file = Files.writeString(tmp.resolve("not-a-dir.txt"), "x");
    List<Path> files = SourceWalker.listFiles(file);
    assertEquals(0, files.size(), "a regular file is not a directory; nothing to walk");
  }

  @Test
  void emptyDirectoryYieldsEmptyList(@TempDir Path tmp) throws IOException {
    Path empty = Files.createDirectories(tmp.resolve("empty"));
    assertEquals(0, SourceWalker.listFiles(empty).size());
  }

  @Test
  void directoryOnlyTreeYieldsNoFiles(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("a/b/c"));
    Files.createDirectories(tmp.resolve("a/d"));
    assertEquals(
        0, SourceWalker.listFiles(tmp).size(), "directories themselves must not be listed");
  }

  @Test
  void deeplyNestedTreeReturnsEveryRegularFile(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("root.txt"), "0");
    Path l1 = Files.createDirectories(tmp.resolve("l1"));
    Files.writeString(l1.resolve("a.txt"), "1");
    Path l2 = Files.createDirectories(l1.resolve("l2"));
    Files.writeString(l2.resolve("b.txt"), "2");
    Path l3 = Files.createDirectories(l2.resolve("l3"));
    Files.writeString(l3.resolve("c.txt"), "3");
    Files.writeString(l3.resolve("d.txt"), "4");

    List<Path> files = SourceWalker.listFiles(tmp);

    assertEquals(5, files.size(), "every file at every depth must be listed");
    assertTrue(files.stream().allMatch(Files::isRegularFile), "only regular files may be returned");
  }

  @Test
  void brokenSymlinkIsSkippedAndWalkCompletes(@TempDir Path tmp) throws IOException {
    Path good = Files.writeString(tmp.resolve("good.txt"), "real");
    Path broken = tmp.resolve("broken-link");
    try {
      Files.createSymbolicLink(broken, tmp.resolve("missing-target"));
    } catch (UnsupportedOperationException | IOException e) {
      assumeTrue(false, "filesystem/OS does not allow symlink creation: " + e.getMessage());
    }

    List<Path> files = SourceWalker.listFiles(tmp);

    assertTrue(
        files.stream().anyMatch(p -> p.getFileName().equals(good.getFileName())),
        "the real file must still be listed");
    assertFalse(
        files.stream().anyMatch(p -> p.getFileName().toString().equals("broken-link")),
        "an unresolvable symlink must not appear as a regular file");
  }
}
