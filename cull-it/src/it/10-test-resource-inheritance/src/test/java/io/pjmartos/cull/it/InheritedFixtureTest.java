package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

class InheritedFixtureTest extends FixtureSupport {

  @Test
  void readsViaInheritance() throws Exception {
    assertNotNull(readFixture());
  }
}
