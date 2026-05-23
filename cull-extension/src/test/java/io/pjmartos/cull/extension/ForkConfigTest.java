package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ForkConfig} — the single fork-detection authority shared by {@link
 * SelectionEngine} and {@link ConfigInjector}. Covers plugin- and execution-level configuration,
 * {@code ${...}} interpolation, Surefire's own user-property fallback, the conservative
 * over-approximation, and the {@code forkMode=none} unification regression.
 */
class ForkConfigTest {

  private static final String SUREFIRE = ConfigInjector.SUREFIRE_GAV;
  private static final String FAILSAFE = ConfigInjector.FAILSAFE_GAV;

  // -- parseForkCountAsZero (canonical implementation now lives here) --

  @Test
  void parseForkCountAsZeroSemantics() {
    assertTrue(ForkConfig.parseForkCountAsZero("0"));
    assertTrue(ForkConfig.parseForkCountAsZero(" 0 "));
    assertTrue(ForkConfig.parseForkCountAsZero("0C"));
    assertTrue(ForkConfig.parseForkCountAsZero("0.0c"));
    assertFalse(ForkConfig.parseForkCountAsZero("1"));
    assertFalse(ForkConfig.parseForkCountAsZero("0.5C"));
    assertFalse(ForkConfig.parseForkCountAsZero(null));
    assertFalse(ForkConfig.parseForkCountAsZero(""));
    assertFalse(ForkConfig.parseForkCountAsZero("abc"));
  }

  // -- plugin-level configuration --

  @Nested
  class PluginLevel {

    @Test
    void forkCountZeroDisables() {
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkCount", "0")));
      assertTrue(ForkConfig.forkDisabled(p, session(), false));
    }

    @Test
    void forkCountZeroCDisables() {
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkCount", "0C")));
      assertTrue(ForkConfig.forkDisabled(p, session(), false));
    }

    @Test
    void forkModeNeverDisables() {
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkMode", "never")));
      assertTrue(ForkConfig.forkDisabled(p, session(), false));
    }

    @Test
    void positiveForkCountDoesNotDisable() {
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkCount", "2C")));
      assertFalse(ForkConfig.forkDisabled(p, session(), false));
    }

    @Test
    void noTestPluginIsNotDisabled() {
      MavenProject p = projectWith(null);
      assertFalse(ForkConfig.forkDisabled(p, session(), false));
    }

    @Test
    void failsafeGavIsConsultedForIntegration() {
      MavenProject p = projectWith(plugin(FAILSAFE, config("forkCount", "0")));
      assertTrue(ForkConfig.forkDisabled(p, session(), true), "integration -> failsafe gav");
      assertFalse(
          ForkConfig.forkDisabled(p, session(), false), "no surefire plugin -> not disabled");
    }
  }

  // -- the forkMode=none unification regression --

  @Nested
  class ForkModeNoneUnification {

    @Test
    void noneDisablesForSurefire() {
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkMode", "none")));
      assertTrue(ForkConfig.forkDisabled(p, session(), false));
    }

    @Test
    void noneDisablesForFailsafeToo() {
      // Regression: the old ConfigInjector.isForkDisabled accepted only
      // forkMode=never, so it would still inject the agent for forkMode=none
      // while SelectionEngine short-circuited -> the two had drifted.
      MavenProject p = projectWith(plugin(FAILSAFE, config("forkMode", "none")));
      assertTrue(ForkConfig.forkDisabled(p, session(), true));
    }
  }

  // -- execution-level configuration (previously a blind spot) --

  @Nested
  class ExecutionLevel {

    @Test
    void forkCountZeroInExecutionDisablesEvenWithoutPluginConfig() {
      Plugin sf = plugin(SUREFIRE, null);
      sf.addExecution(execution("default-test", config("forkCount", "0")));
      MavenProject p = projectWith(sf);
      assertTrue(ForkConfig.forkDisabled(p, session(), false));
    }

    @Test
    void forkModeNoneInExecutionDisables() {
      Plugin sf = plugin(SUREFIRE, null);
      sf.addExecution(execution("default-test", config("forkMode", "none")));
      MavenProject p = projectWith(sf);
      assertTrue(ForkConfig.forkDisabled(p, session(), false));
    }

    @Test
    void executionOverridesPluginToDisable() {
      Plugin sf = plugin(SUREFIRE, config("forkCount", "3"));
      sf.addExecution(execution("default-test", config("forkCount", "0")));
      MavenProject p = projectWith(sf);
      assertTrue(
          ForkConfig.forkDisabled(p, session(), false),
          "execution forkCount=0 wins over plugin forkCount=3");
    }

    @Test
    void pluginLevelDisableStaysConservativeDespiteExecutionOverride() {
      Plugin sf = plugin(SUREFIRE, config("forkCount", "0"));
      sf.addExecution(execution("extra", config("forkCount", "4")));
      MavenProject p = projectWith(sf);
      assertTrue(
          ForkConfig.forkDisabled(p, session(), false),
          "any non-forking candidate config marks the project non-forking (safe direction)");
    }

    @Test
    void positiveEverywhereDoesNotDisable() {
      Plugin sf = plugin(SUREFIRE, config("forkCount", "1C"));
      sf.addExecution(execution("default-test", config("forkCount", "2")));
      MavenProject p = projectWith(sf);
      assertFalse(ForkConfig.forkDisabled(p, session(), false));
    }
  }

  // -- ${...} interpolation --

  @Nested
  class Interpolation {

    @Test
    void userPropertyResolvesForkCountToZero() {
      Properties user = new Properties();
      user.setProperty("my.fork.count", "0");
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkCount", "${my.fork.count}")));
      assertTrue(ForkConfig.forkDisabled(p, session(user, new Properties()), false));
    }

    @Test
    void userPropertyResolvesForkCountToNonZero() {
      Properties user = new Properties();
      user.setProperty("my.fork.count", "3");
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkCount", "${my.fork.count}")));
      assertFalse(ForkConfig.forkDisabled(p, session(user, new Properties()), false));
    }

    @Test
    void systemPropertyResolvesForkMode() {
      Properties sys = new Properties();
      sys.setProperty("fork.mode.prop", "never");
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkMode", "${fork.mode.prop}")));
      assertTrue(ForkConfig.forkDisabled(p, session(new Properties(), sys), false));
    }

    @Test
    void projectPropertyResolvesForkCount() {
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkCount", "${proj.forks}")));
      p.getProperties().setProperty("proj.forks", "0");
      assertTrue(ForkConfig.forkDisabled(p, session(), false));
    }

    @Test
    void unresolvedPlaceholderIsTreatedAsNotDisabling() {
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkCount", "${nobody.sets.this}")));
      assertFalse(
          ForkConfig.forkDisabled(p, session(), false),
          "an unresolvable placeholder must not be misread as forkCount=0");
    }

    @Test
    void userPropertyTakesPrecedenceOverSystemProperty() {
      Properties user = new Properties();
      user.setProperty("forks", "0");
      Properties sys = new Properties();
      sys.setProperty("forks", "8");
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkCount", "${forks}")));
      assertTrue(ForkConfig.forkDisabled(p, session(user, sys), false));
    }
  }

  // -- Surefire's own user-property fallback (no POM config at all) --

  @Nested
  class UserPropertyFallback {

    @Test
    void dashDForkCountZeroDisablesWithoutPomConfig() {
      Properties user = new Properties();
      user.setProperty("forkCount", "0");
      MavenProject p = projectWith(plugin(SUREFIRE, null));
      assertTrue(ForkConfig.forkDisabled(p, session(user, new Properties()), false));
    }

    @Test
    void dashDForkModeNoneDisablesWithoutPomConfig() {
      Properties user = new Properties();
      user.setProperty("forkMode", "none");
      MavenProject p = projectWith(plugin(SUREFIRE, null));
      assertTrue(ForkConfig.forkDisabled(p, session(user, new Properties()), false));
    }

    @Test
    void explicitPomForkCountBeatsDashDForkCount() {
      Properties user = new Properties();
      user.setProperty("forkCount", "0");
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkCount", "3")));
      assertFalse(
          ForkConfig.forkDisabled(p, session(user, new Properties()), false),
          "an explicit POM forkCount must win over the -DforkCount property");
    }

    @Test
    void nullSessionIsTolerated() {
      MavenProject p = projectWith(plugin(SUREFIRE, config("forkCount", "2")));
      assertFalse(ForkConfig.forkDisabled(p, null, false));
    }
  }

  // -- helpers --

  private static MavenSession session() {
    return TestSessionFactory.createSession(new Properties(), new Properties());
  }

  private static MavenSession session(Properties user, Properties sys) {
    return TestSessionFactory.createSession(user, sys);
  }

  private static MavenProject projectWith(Plugin testPlugin) {
    MavenProject p = new MavenProject();
    p.setGroupId("io.pjmartos.cull.test");
    p.setArtifactId("forkcfg");
    p.setVersion("1.0");
    Build b = new Build();
    if (testPlugin != null) {
      b.addPlugin(testPlugin);
    }
    p.setBuild(b);
    b.flushPluginMap();
    return p;
  }

  private static Plugin plugin(String gav, Xpp3Dom configuration) {
    String[] parts = gav.split(":");
    Plugin plugin = new Plugin();
    plugin.setGroupId(parts[0]);
    plugin.setArtifactId(parts[1]);
    plugin.setVersion("3.2.5");
    if (configuration != null) {
      plugin.setConfiguration(configuration);
    }
    return plugin;
  }

  private static PluginExecution execution(String id, Xpp3Dom configuration) {
    PluginExecution e = new PluginExecution();
    e.setId(id);
    e.setConfiguration(configuration);
    return e;
  }

  private static Xpp3Dom config(String child, String value) {
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom c = new Xpp3Dom(child);
    c.setValue(value);
    cfg.addChild(c);
    return cfg;
  }
}
