package io.pjmartos.cull.it;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class IoHookTriggerTest {

  @Test
  void readsClasspathResourceViaInstrumentedMethod() throws Exception {
    try (InputStream in = getClass().getResourceAsStream("/fixture.txt")) {
      assertNotNull(in, "fixture resource must be on test classpath");
      byte[] buf = in.readAllBytes();
      String content = new String(buf, StandardCharsets.UTF_8);
      assertTrue(content.startsWith("resource-payload"));
      assertNotNull(new Greeter().greet("ioHooks"));
    }
  }
}
