package io.pjmartos.cull.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class CrossRefTest {

  @Test
  void valueEqualityAndHashing() {
    CrossRef a = new CrossRef("g:a", "com.example.V");
    CrossRef b = new CrossRef("g:a", "com.example.V");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
    assertNotEquals(new CrossRef("g:a", "com.example.U"), a);
    assertNotEquals(new CrossRef("g:b", "com.example.V"), a);
  }

  @Test
  void orderingByModuleThenClass() {
    CrossRef av = new CrossRef("g:a", "com.example.V");
    CrossRef au = new CrossRef("g:a", "com.example.U");
    CrossRef bv = new CrossRef("g:b", "com.example.V");
    assertTrue(au.compareTo(av) < 0, "same module orders by class name");
    assertTrue(av.compareTo(bv) < 0, "module id is the primary key");
  }

  @Test
  void accessorsAndToString() {
    CrossRef cr = new CrossRef("com.example:mod-a", "com.example.a.V");
    assertEquals("com.example:mod-a", cr.moduleId());
    assertEquals("com.example.a.V", cr.className());
    assertEquals("com.example:mod-a/com.example.a.V", cr.toString());
  }

  @Test
  void nullComponentsRejected() {
    assertThrows(NullPointerException.class, () -> new CrossRef(null, "C"));
    assertThrows(NullPointerException.class, () -> new CrossRef("g:a", null));
  }
}
