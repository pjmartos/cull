package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Exercises only {@link Standalone}; it has no static or runtime dependency on the upstream module,
 * so a change to {@code upstream.Shared} must NOT re-select this test under cross-module {@code
 * full} (precision), while {@code ConsumerTest} — which does depend on {@code Shared} — must be
 * re-selected (soundness).
 */
class StandaloneTest {

  @Test
  void answers() {
    assertEquals(7, new Standalone().answer());
  }
}
