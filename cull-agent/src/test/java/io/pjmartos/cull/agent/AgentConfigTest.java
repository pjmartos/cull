package io.pjmartos.cull.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentConfigTest {

  @Test
  void parseWithObservationsDir() {
    AgentConfig cfg = AgentConfig.parse("observations=/tmp/obs,forkId=f1");
    assertTrue(cfg.observationFile().toString().replace('\\', '/').contains("/tmp/obs"));
    assertEquals("f1", cfg.forkId());
  }

  @Test
  void parseDefaultsWhenNoArgs() {
    AgentConfig cfg = AgentConfig.parse("");
    assertNotNull(cfg.observationFile());
    assertNotNull(cfg.forkId());
  }

  @Test
  void parseWithNullArgs() {
    AgentConfig cfg = AgentConfig.parse(null);
    assertNotNull(cfg.observationFile());
    assertNotNull(cfg.forkId());
  }

  @Test
  void ioHooksDisabledViaArgs() {
    AgentConfig cfg = AgentConfig.parse("iohooksDisabled=true");
    assertTrue(cfg.ioHooksDisabled());
  }

  @Test
  void ioHooksEnabledByDefault() {
    AgentConfig cfg = AgentConfig.parse("");
    assertFalse(cfg.ioHooksDisabled());
  }

  @Test
  void forkIdFromArgs() {
    AgentConfig cfg = AgentConfig.parse("forkId=custom-fork");
    assertEquals("custom-fork", cfg.forkId());
  }

  @Test
  void observationFileContainsForkIdAndTimestamp() {
    AgentConfig cfg = AgentConfig.parse("observations=/x,forkId=f1");
    Path f = cfg.observationFile();
    String name = f.getFileName().toString();
    assertTrue(name.startsWith("observations-f1-"));
    assertTrue(name.endsWith(".bin"));
  }
}
