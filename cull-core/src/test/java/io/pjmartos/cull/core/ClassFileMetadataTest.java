package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClassFileMetadataTest {

  private static ClassFileMetadata sample() {
    return new ClassFileMetadata(
        "a/B", "java/lang/Object", List.of("a/I"), Set.of("a/C", "a/D"), 55);
  }

  @Test
  void accessorsExposeConstructorArguments() {
    ClassFileMetadata m = sample();
    assertEquals("a/B", m.thisClass());
    assertEquals("java/lang/Object", m.superClass());
    assertEquals(List.of("a/I"), m.interfaces());
    assertEquals(Set.of("a/C", "a/D"), m.referencedClasses());
    assertEquals(55, m.classFileVersion());
  }

  @SuppressWarnings("DataFlowIssue")
  @Test
  void interfacesAndReferencedAreUnmodifiable() {
    ClassFileMetadata m = sample();
    assertThrows(UnsupportedOperationException.class, () -> m.interfaces().add("x"));
    assertThrows(UnsupportedOperationException.class, () -> m.referencedClasses().add("x"));
  }

  @Test
  void nullCollectionsAreRejectedByConstructor() {
    assertThrows(
        NullPointerException.class, () -> new ClassFileMetadata("a/B", "a/S", null, Set.of(), 52));
    assertThrows(
        NullPointerException.class, () -> new ClassFileMetadata("a/B", "a/S", List.of(), null, 52));
  }

  @Test
  void equalsAndHashCodeFollowValueSemantics() {
    ClassFileMetadata a = sample();
    ClassFileMetadata b = sample();
    assertEquals(a, b);
    assertEquals(b, a);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotNull(a);
  }

  @Test
  void differingAnyFieldBreaksEquality() {
    ClassFileMetadata base = sample();
    assertNotEquals(
        base,
        new ClassFileMetadata("a/X", "java/lang/Object", List.of("a/I"), Set.of("a/C", "a/D"), 55));
    assertNotEquals(
        base, new ClassFileMetadata("a/B", "a/Other", List.of("a/I"), Set.of("a/C", "a/D"), 55));
    assertNotEquals(
        base,
        new ClassFileMetadata("a/B", "java/lang/Object", List.of(), Set.of("a/C", "a/D"), 55));
    assertNotEquals(
        base, new ClassFileMetadata("a/B", "java/lang/Object", List.of("a/I"), Set.of("a/C"), 55));
    assertNotEquals(
        base,
        new ClassFileMetadata("a/B", "java/lang/Object", List.of("a/I"), Set.of("a/C", "a/D"), 61));
  }

  @Test
  void nullSuperClassIsPermittedAndComparesEqual() {
    ClassFileMetadata a = new ClassFileMetadata("a/B", null, List.of(), Set.of(), 65);
    ClassFileMetadata b = new ClassFileMetadata("a/B", null, List.of(), Set.of(), 65);
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertTrue(a.interfaces().isEmpty());
  }
}
