package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Properties;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CoverageRetention} — the JaCoCo coverage baseline seed/persist logic. Covers
 * destFile/append resolution from plugin and execution configuration, the enable/opt-out gating,
 * the seed-before / persist-after copies, and the realistic round-trip.
 */
class CoverageRetentionTest {

  private static final String CHECKSUM = "abc123def456";

  // -- configuration resolution --

  @Nested
  class Resolve {

    @Test
    void nullWhenJacocoAbsent(@TempDir Path tmp) {
      MavenProject p = project(tmp, plugin("org.apache.maven.plugins", "maven-surefire-plugin"));
      assertNull(CoverageRetention.resolve(p, session(), false));
    }

    @Test
    void nullWhenExplicitlyDisabledEvenWithJacoco(@TempDir Path tmp) {
      MavenProject p = project(tmp, jacoco());
      Properties user = new Properties();
      user.setProperty(CullProperties.COVERAGE_RETAIN, "false");
      assertNull(CoverageRetention.resolve(p, session(user), false));
    }

    @Test
    void defaultUnitDestFileIsJacocoExec(@TempDir Path tmp) {
      MavenProject p = project(tmp, jacoco());
      CoverageRetention.Resolved r = CoverageRetention.resolve(p, session(), false);
      assertNotNull(r);
      assertEquals(buildDir(tmp).resolve("jacoco.exec"), r.destFile);
    }

    @Test
    void defaultIntegrationDestFileIsJacocoItExec(@TempDir Path tmp) {
      MavenProject p = project(tmp, jacoco());
      CoverageRetention.Resolved r = CoverageRetention.resolve(p, session(), true);
      assertNotNull(r);
      assertEquals(buildDir(tmp).resolve("jacoco-it.exec"), r.destFile);
    }

    @Test
    void explicitDestFileFromExecutionConfigIsHonored(@TempDir Path tmp) {
      Plugin jacoco =
          jacoco(
              null,
              exec(
                  CoverageRetention.PREPARE_AGENT,
                  configWith("destFile", "${project.build.directory}/coverage/unit.exec")));
      MavenProject p = project(tmp, jacoco);
      CoverageRetention.Resolved r = CoverageRetention.resolve(p, session(), false);
      assertNotNull(r);
      assertEquals(buildDir(tmp).resolve("coverage").resolve("unit.exec"), r.destFile);
    }

    @Test
    void pluginLevelDestFileUsedWhenExecutionHasNone(@TempDir Path tmp) {
      Plugin jacoco =
          jacoco(
              configWith("destFile", "${project.build.directory}/plugin-level.exec"),
              exec(CoverageRetention.PREPARE_AGENT, null));
      MavenProject p = project(tmp, jacoco);
      CoverageRetention.Resolved r = CoverageRetention.resolve(p, session(), false);
      assertNotNull(r);
      assertEquals(buildDir(tmp).resolve("plugin-level.exec"), r.destFile);
    }

    @Test
    void relativeDestFileResolvedAgainstBasedir(@TempDir Path tmp) {
      Plugin jacoco =
          jacoco(null, exec(CoverageRetention.PREPARE_AGENT, configWith("destFile", "cov.exec")));
      MavenProject p = project(tmp, jacoco);
      CoverageRetention.Resolved r = CoverageRetention.resolve(p, session(), false);
      assertNotNull(r);
      assertEquals(tmp.resolve("cov.exec"), r.destFile);
    }

    @Test
    void appendFalseInConfigDisablesRetention(@TempDir Path tmp) {
      Plugin jacoco =
          jacoco(null, exec(CoverageRetention.PREPARE_AGENT, configWith("append", "false")));
      MavenProject p = project(tmp, jacoco);
      assertNull(CoverageRetention.resolve(p, session(), false));
    }

    @Test
    void appendFalseViaPropertyDisablesRetention(@TempDir Path tmp) {
      MavenProject p = project(tmp, jacoco());
      Properties user = new Properties();
      user.setProperty("jacoco.append", "false");
      assertNull(CoverageRetention.resolve(p, session(user), false));
    }

    @Test
    void integrationAppendReadFromIntegrationExecution(@TempDir Path tmp) {
      // append=false only on the unit execution must not disable the IT path.
      Plugin jacoco =
          jacoco(
              null,
              exec(CoverageRetention.PREPARE_AGENT, configWith("append", "false")),
              exec(CoverageRetention.PREPARE_AGENT_IT, null));
      MavenProject p = project(tmp, jacoco);
      assertNull(CoverageRetention.resolve(p, session(), false), "unit path disabled");
      assertNotNull(CoverageRetention.resolve(p, session(), true), "IT path still enabled");
    }
  }

  // -- restore (seed before tests) --

  @Nested
  class Restore {

    @Test
    void copiesBaselineIntoDestFileCreatingParents(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      byte[] data = {1, 2, 3, 4, 5};
      Files.write(cacheBase.resolve(CoverageRetention.cacheFileName(CHECKSUM, false)), data);
      MavenProject p = project(tmp, jacoco());

      CoverageRetention.restore(p, session(), CHECKSUM, false, cacheBase);

      Path dest = buildDir(tmp).resolve("jacoco.exec");
      assertTrue(Files.isRegularFile(dest), "destFile must be seeded under target/");
      assertArrayEquals(data, Files.readAllBytes(dest));
    }

    @Test
    void noOpWhenBaselineMissing(@TempDir Path tmp) {
      MavenProject p = project(tmp, jacoco());
      CoverageRetention.restore(p, session(), CHECKSUM, false, tmp.resolve("cache"));
      assertFalse(Files.exists(buildDir(tmp).resolve("jacoco.exec")));
    }

    @Test
    void noOpWhenAppendFalse(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      Files.write(
          cacheBase.resolve(CoverageRetention.cacheFileName(CHECKSUM, false)), new byte[] {9});
      Plugin jacoco =
          jacoco(null, exec(CoverageRetention.PREPARE_AGENT, configWith("append", "false")));
      MavenProject p = project(tmp, jacoco);

      CoverageRetention.restore(p, session(), CHECKSUM, false, cacheBase);

      assertFalse(
          Files.exists(buildDir(tmp).resolve("jacoco.exec")),
          "overwrite-mode JaCoCo must not be seeded");
    }

    @Test
    void noOpWhenChecksumNull(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      MavenProject p = project(tmp, jacoco());
      CoverageRetention.restore(p, session(), null, false, cacheBase);
      assertFalse(Files.exists(buildDir(tmp).resolve("jacoco.exec")));
    }

    @Test
    void noOpWhenJacocoAbsent(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      Files.write(
          cacheBase.resolve(CoverageRetention.cacheFileName(CHECKSUM, false)), new byte[] {7});
      MavenProject p = project(tmp, plugin("org.apache.maven.plugins", "maven-surefire-plugin"));
      CoverageRetention.restore(p, session(), CHECKSUM, false, cacheBase);
      assertFalse(Files.exists(buildDir(tmp).resolve("jacoco.exec")));
    }
  }

  // -- persist (after a passing commit) --

  @Nested
  class Persist {

    @Test
    void copiesDestFileIntoCacheBaseline(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      MavenProject p = project(tmp, jacoco());
      Path dest = buildDir(tmp).resolve("jacoco.exec");
      Files.createDirectories(dest.getParent());
      byte[] data = {10, 20, 30};
      Files.write(dest, data);

      CoverageRetention.persist(p, session(), CHECKSUM, false, cacheBase);

      Path baseline = cacheBase.resolve(CoverageRetention.cacheFileName(CHECKSUM, false));
      assertTrue(Files.isRegularFile(baseline));
      assertArrayEquals(data, Files.readAllBytes(baseline));
    }

    @Test
    void noOpWhenDestFileMissing(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      MavenProject p = project(tmp, jacoco());
      CoverageRetention.persist(p, session(), CHECKSUM, false, cacheBase);
      assertFalse(
          Files.exists(cacheBase.resolve(CoverageRetention.cacheFileName(CHECKSUM, false))));
    }

    @Test
    void noOpWhenAppendFalse(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      Plugin jacoco =
          jacoco(null, exec(CoverageRetention.PREPARE_AGENT, configWith("append", "false")));
      MavenProject p = project(tmp, jacoco);
      Path dest = buildDir(tmp).resolve("jacoco.exec");
      Files.createDirectories(dest.getParent());
      Files.write(dest, new byte[] {1});

      CoverageRetention.persist(p, session(), CHECKSUM, false, cacheBase);

      assertFalse(
          Files.exists(cacheBase.resolve(CoverageRetention.cacheFileName(CHECKSUM, false))),
          "subset-only result must not shrink the baseline under overwrite mode");
    }
  }

  // -- baselineNeedsBootstrap (force a full run when retention is active but unseeded) --

  @Nested
  class BaselineNeedsBootstrap {

    @Test
    void trueWhenJacocoActiveAndBaselineMissing(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      MavenProject p = project(tmp, jacoco());
      assertTrue(
          CoverageRetention.baselineNeedsBootstrap(p, session(), CHECKSUM, false, cacheBase));
    }

    @Test
    void falseWhenBaselinePresent(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      Files.write(
          cacheBase.resolve(CoverageRetention.cacheFileName(CHECKSUM, false)), new byte[] {1});
      MavenProject p = project(tmp, jacoco());
      assertFalse(
          CoverageRetention.baselineNeedsBootstrap(p, session(), CHECKSUM, false, cacheBase));
    }

    @Test
    void falseWhenRetentionDisabledByAppendFalse(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      Plugin jacoco =
          jacoco(null, exec(CoverageRetention.PREPARE_AGENT, configWith("append", "false")));
      MavenProject p = project(tmp, jacoco);
      assertFalse(
          CoverageRetention.baselineNeedsBootstrap(p, session(), CHECKSUM, false, cacheBase),
          "overwrite-mode JaCoCo retains nothing, so there is no baseline to bootstrap");
    }

    @Test
    void falseWhenRetentionOptedOut(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      MavenProject p = project(tmp, jacoco());
      Properties user = new Properties();
      user.setProperty(CullProperties.COVERAGE_RETAIN, "false");
      assertFalse(
          CoverageRetention.baselineNeedsBootstrap(p, session(user), CHECKSUM, false, cacheBase));
    }

    @Test
    void falseWhenJacocoAbsent(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      MavenProject p = project(tmp, plugin("org.apache.maven.plugins", "maven-surefire-plugin"));
      assertFalse(
          CoverageRetention.baselineNeedsBootstrap(p, session(), CHECKSUM, false, cacheBase));
    }

    @Test
    void falseWhenChecksumNull(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      MavenProject p = project(tmp, jacoco());
      assertFalse(CoverageRetention.baselineNeedsBootstrap(p, session(), null, false, cacheBase));
    }

    @Test
    void falseForPhaseWhosePrepareAgentGoalIsNotBound(@TempDir Path tmp) throws IOException {
      // Only the unit prepare-agent is bound. The integration phase records no coverage, so a
      // missing jacoco-it.exec baseline must NOT force the whole IT suite to run.
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      MavenProject p = project(tmp, jacoco());
      assertTrue(
          CoverageRetention.baselineNeedsBootstrap(p, session(), CHECKSUM, false, cacheBase),
          "unit phase has prepare-agent bound and no baseline");
      assertFalse(
          CoverageRetention.baselineNeedsBootstrap(p, session(), CHECKSUM, true, cacheBase),
          "integration phase has no prepare-agent-integration bound");
    }

    @Test
    void trueForIntegrationWhenIntegrationGoalBound(@TempDir Path tmp) throws IOException {
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      Plugin jacoco = jacoco(null, exec(CoverageRetention.PREPARE_AGENT_IT, null));
      MavenProject p = project(tmp, jacoco);
      assertTrue(CoverageRetention.baselineNeedsBootstrap(p, session(), CHECKSUM, true, cacheBase));
    }

    @Test
    void readsGoalFromPluginLevelGoalsNotOnlyExecutionId(@TempDir Path tmp) throws IOException {
      // The execution id differs from the goal; binding is detected by the goal, not the id.
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      PluginExecution e = new PluginExecution();
      e.setId("agent");
      e.setGoals(Collections.singletonList(CoverageRetention.PREPARE_AGENT));
      MavenProject p = project(tmp, jacoco(null, e));
      assertTrue(
          CoverageRetention.baselineNeedsBootstrap(p, session(), CHECKSUM, false, cacheBase));
    }
  }

  @Test
  void persistThenRestoreRoundTripsBytes(@TempDir Path tmp) throws IOException {
    Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
    MavenProject p = project(tmp, jacoco());
    Path dest = buildDir(tmp).resolve("jacoco-it.exec");
    Files.createDirectories(dest.getParent());
    byte[] data = "exec-payload".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(dest, data);

    CoverageRetention.persist(p, session(), CHECKSUM, true, cacheBase);
    Files.delete(dest); // simulate a `mvn clean` wiping target/
    CoverageRetention.restore(p, session(), CHECKSUM, true, cacheBase);

    assertArrayEquals(data, Files.readAllBytes(dest));
  }

  @Test
  void cacheFileNameDistinguishesUnitFromIntegration() {
    assertEquals("k.jacoco.exec", CoverageRetention.cacheFileName("k", false));
    assertEquals("k.jacoco-it.exec", CoverageRetention.cacheFileName("k", true));
  }

  // -- helpers --

  private static MavenSession session() {
    return session(new Properties());
  }

  private static MavenSession session(Properties user) {
    return TestSessionFactory.createSession(user, new Properties());
  }

  private static Path buildDir(Path basedir) {
    return basedir.resolve("target");
  }

  private static MavenProject project(Path basedir, Plugin... plugins) {
    MavenProject p = new MavenProject();
    p.setGroupId("io.pjmartos.cull.test");
    p.setArtifactId("covtarget");
    p.setVersion("1.0");
    p.setFile(basedir.resolve("pom.xml").toFile());
    Build b = new Build();
    b.setDirectory(buildDir(basedir).toString());
    for (Plugin pl : plugins) {
      b.addPlugin(pl);
    }
    p.setBuild(b);
    b.flushPluginMap();
    return p;
  }

  private static Plugin plugin(String groupId, String artifactId) {
    Plugin pl = new Plugin();
    pl.setGroupId(groupId);
    pl.setArtifactId(artifactId);
    pl.setVersion("1.0");
    return pl;
  }

  private static Plugin jacoco(Xpp3Dom pluginConfig, PluginExecution... execs) {
    Plugin pl = plugin("org.jacoco", "jacoco-maven-plugin");
    pl.setVersion("0.8.12");
    if (pluginConfig != null) {
      pl.setConfiguration(pluginConfig);
    }
    for (PluginExecution e : execs) {
      pl.addExecution(e);
    }
    return pl;
  }

  private static Plugin jacoco() {
    return jacoco(null, exec(CoverageRetention.PREPARE_AGENT, null));
  }

  private static PluginExecution exec(String goal, Xpp3Dom cfg) {
    PluginExecution e = new PluginExecution();
    e.setId(goal);
    e.setGoals(Collections.singletonList(goal));
    if (cfg != null) {
      e.setConfiguration(cfg);
    }
    return e;
  }

  private static Xpp3Dom configWith(String name, String value) {
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom child = new Xpp3Dom(name);
    child.setValue(value);
    cfg.addChild(child);
    return cfg;
  }
}
