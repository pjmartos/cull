package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.InputStream;
import org.junit.jupiter.api.Test;

class IsolatedTest {

  @Test
  void readsIsolatedResource() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/isolated.txt")) {
      assertNotNull(in);
      assertNotNull(new String(in.readAllBytes()));
    }
  }
}
