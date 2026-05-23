package io.pjmartos.cull.agent.listeners;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class TestClassNamesTest {

  @Test
  void topLevelLeavesPlainNamesAlone() {
    assertEquals("com.example.OuterTest", TestClassNames.topLevel("com.example.OuterTest"));
  }

  @Test
  void topLevelStripsNestedSegment() {
    assertEquals("com.example.OuterTest", TestClassNames.topLevel("com.example.OuterTest$Inner"));
  }

  @Test
  void topLevelStripsMultipleNestingLevels() {
    assertEquals("com.example.OuterTest", TestClassNames.topLevel("com.example.OuterTest$A$B$C"));
  }

  @Test
  void topLevelHandlesNull() {
    assertNull(TestClassNames.topLevel(null));
  }
}
