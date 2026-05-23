package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;

class RelPathTest {

  @Test
  void ofStoresCanonicalValue() {
    RelPath r = RelPath.of("src/main/java/A.java");
    assertEquals("src/main/java/A.java", r.value());
  }

  @Test
  void ofRejectsNull() {
    assertThrows(NullPointerException.class, () -> RelPath.of(null));
  }

  @Test
  void ofRejectsEmpty() {
    assertThrows(IllegalArgumentException.class, () -> RelPath.of(""));
  }

  @Test
  void relativizeProducesForwardSlashPath() {
    Path base = Paths.get("/project").toAbsolutePath();
    Path child = Paths.get("/project/src/main/java/A.java").toAbsolutePath();
    RelPath r = RelPath.relativize(base, child);
    assertEquals("src/main/java/A.java", r.value());
  }

  @Test
  void resolveAgainstRebuildsAbsolutePath() {
    RelPath r = RelPath.of("src/main/java/A.java");
    Path base = Paths.get("/project");
    Path resolved = r.resolveAgainst(base);
    assertTrue(resolved.toString().replace('\\', '/').endsWith("src/main/java/A.java"));
  }

  @Test
  void compareToOrdersLexicographically() {
    RelPath a = RelPath.of("a");
    RelPath b = RelPath.of("b");
    assertTrue(a.compareTo(b) < 0);
    assertEquals(0, a.compareTo(RelPath.of("a")));
  }

  @Test
  void equalsAndHashCode() {
    RelPath a1 = RelPath.of("x");
    RelPath a2 = RelPath.of("x");
    assertEquals(a1, a2);
    assertEquals(a1.hashCode(), a2.hashCode());
  }

  @Test
  void toStringReturnsValue() {
    assertEquals("p", RelPath.of("p").toString());
  }
}
