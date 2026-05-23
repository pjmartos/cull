package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Tests for {@link SelectionOutcome} — the simple data holder produced by SelectionEngine. */
class SelectionOutcomeTest {

  @Test
  void constructsWithFullArgs() {
    Set<String> selected = Set.of("com.example.FooTest");
    SelectionOutcome o =
        new SelectionOutcome(selected, false, "sid-1", Path.of("/tmp/staging"), "abc123", null);
    assertEquals(1, o.selected.size());
    assertFalse(o.wildcard);
    assertEquals("sid-1", o.sessionId);
    assertEquals(Path.of("/tmp/staging"), o.stagingDir);
    assertEquals("abc123", o.projectChecksum);
    assertNull(o.reasonForWildcard);
  }

  @Test
  void wildcardFactoryAndPredicate() {
    SelectionOutcome o = SelectionOutcome.wildcard("test reason");
    assertTrue(o.wildcard);
    assertTrue(o.selected.isEmpty());
    assertNull(o.sessionId);
    assertEquals("test reason", o.reasonForWildcard);
  }

  @Test
  void wildcardReturnsStar() {
    assertEquals("*", SelectionOutcome.wildcard("x").selectedTestsString());
  }

  @Test
  void emptySelectionReturnsNoMatchPattern() {
    SelectionOutcome o = new SelectionOutcome(Set.of(), false, "sid", Path.of("/tmp"), "chk", null);
    assertEquals(SelectionOutcome.NO_MATCH_PATTERN, o.selectedTestsString());
  }

  @Test
  void nonEmptySelectionReturnsCommaSeparated() {
    SelectionOutcome o =
        new SelectionOutcome(
            Set.of("com.example.FooTest", "com.example.BarTest"),
            false,
            "sid",
            Path.of("/tmp"),
            "chk",
            null);
    String result = o.selectedTestsString();
    assertTrue(result.contains("com.example.FooTest"));
    assertTrue(result.contains("com.example.BarTest"));
    assertTrue(result.contains(","));
  }
}
