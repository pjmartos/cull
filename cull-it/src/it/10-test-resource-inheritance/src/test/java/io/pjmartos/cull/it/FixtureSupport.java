package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;

abstract class FixtureSupport {

  protected String readFixture() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/fixture.txt")) {
      assertNotNull(in);
      return new String(in.readAllBytes()).trim();
    }
  }
}
