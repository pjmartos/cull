package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.Test;

class CullVersionTest {

  @Test
  void resolvesTheBuildingReactorVersion() {
    String expected = System.getProperty("cull.test.projectVersion");
    assumeTrue(expected != null && !expected.isEmpty(), "requires Maven-injected project version");

    assertEquals(expected, CullVersion.get());
  }

  @Test
  void resolvedVersionIsConcreteNotAnUnfilteredPlaceholder() {
    assumeTrue(System.getProperty("cull.test.projectVersion") != null, "requires a Maven build");

    String v = CullVersion.get();
    assertFalse(v.isEmpty(), "version must not be blank");
    assertFalse(v.startsWith("${"), "version must be filtered, not a ${...} placeholder");
  }
}
