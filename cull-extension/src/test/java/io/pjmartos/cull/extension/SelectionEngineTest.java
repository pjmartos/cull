package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.core.RelPath;
import io.pjmartos.cull.core.TestGraph;
import io.pjmartos.cull.core.TestGraphCodec;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Collections;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SelectionEngine} — the selection orchestrator — covering fork-count parsing,
 * session-state file path resolution, stale-lock cleanup, opportunistic GC, and the full {@code
 * runFor} degradation/bootstrap pipeline.
 */
class SelectionEngineTest {

  // -- parseForkCountAsZero --

  @Test
  void parseForkCountAsZeroAcceptsZeroLiteral() {
    assertTrue(SelectionEngine.parseForkCountAsZero("0"));
    assertTrue(SelectionEngine.parseForkCountAsZero(" 0 "));
  }

  @Test
  void parseForkCountAsZeroAcceptsZeroMultiplier() {
    assertTrue(SelectionEngine.parseForkCountAsZero("0C"));
    assertTrue(SelectionEngine.parseForkCountAsZero("0.0C"));
    assertTrue(SelectionEngine.parseForkCountAsZero("0c"));
  }

  @Test
  void parseForkCountAsZeroRejectsNonZero() {
    assertFalse(SelectionEngine.parseForkCountAsZero("1"));
    assertFalse(SelectionEngine.parseForkCountAsZero("2C"));
    assertFalse(SelectionEngine.parseForkCountAsZero("0.5C"));
  }

  @Test
  void parseForkCountAsZeroRejectsEmptyAndNull() {
    assertFalse(SelectionEngine.parseForkCountAsZero(null));
    assertFalse(SelectionEngine.parseForkCountAsZero(""));
    assertFalse(SelectionEngine.parseForkCountAsZero("abc"));
    assertFalse(SelectionEngine.parseForkCountAsZero("xC"));
  }

  // -- sessionStateFile --

  @Test
  void sessionStateFileResolvesToBuildDir() {
    MavenProject project = new MavenProject();
    project.getBuild().setDirectory("target");
    Path f = SelectionEngine.sessionStateFile(project, false);
    assertTrue(f.toString().replace('\\', '/').endsWith("target/cull/session-state.bin"));
  }

  @Test
  void sessionStateFileResolvesToBuildDirForIT() {
    MavenProject project = new MavenProject();
    project.getBuild().setDirectory("target");
    Path f = SelectionEngine.sessionStateFile(project, true);
    assertTrue(f.toString().replace('\\', '/').endsWith("target/cull/session-state-it.bin"));
  }

  // -- cleanupStaleLock boundary tests --

  @Test
  void cleanupStaleLockHandlesMissingParent(@TempDir Path dir) {
    Path missing = dir.resolve("nonexistent").resolve(".lock");
    SelectionEngine.cleanupStaleLock(missing);
  }

  // -- runOpportunisticGc edge cases --

  @Test
  void runOpportunisticGcNoopWhenCacheBaseDoesNotExist(@TempDir Path dir) {
    Path nonexistent = dir.resolve("no-such-cache");
    SelectionEngine.runOpportunisticGc(nonexistent, 5);
  }

  @Test
  void runOpportunisticGcNegativeRetentionBehavesLikeZero(@TempDir Path cacheBase)
      throws IOException {
    Path a = cacheBase.resolve("a.state.bin");
    Files.writeString(a, "a");
    SelectionEngine.runOpportunisticGc(cacheBase, -1);
    assertTrue(Files.exists(a));
  }

  // -- readLastUsed edge cases --

  @Test
  void readLastUsedReturnsMtimeWhenCompanionMissing(@TempDir Path dir) throws IOException {
    Path state = dir.resolve("chk.state.bin");
    Files.writeString(state, "data");
    String val = SelectionEngine.readLastUsed(state);
    assertNotNull(val);
    assertFalse(val.isEmpty(), "should return mtime string when .last_used companion is absent");
  }

  @Test
  void readLastUsedReturnsEmptyForMissingStateFile(@TempDir Path dir) {
    Path state = dir.resolve("missing.state.bin");
    assertEquals("", SelectionEngine.readLastUsed(state));
  }

  /**
   * Exercises {@link SelectionEngine#runFor} degradation exits, bootstrap, and wildcard collapse.
   */
  @Nested
  class RunFor {

    private MavenProject registeredProject;

    @AfterEach
    void releaseAnyRegisteredSession() {
      if (registeredProject != null) {
        CullSession cs = CullSessionRegistry.remove(registeredProject, false);
        if (cs != null) {
          cs.rollback();
        }
        registeredProject = null;
      }
    }

    @Test
    void disabledShortCircuitsToWildcard() {
      Properties user = new Properties();
      user.setProperty(CullProperties.DISABLED, "true");
      MavenSession session = TestSessionFactory.createSession(user, new Properties());
      SelectionOutcome o = SelectionEngine.runFor(new MavenProject(), session, false);
      assertTrue(o.wildcard);
      assertEquals("cull disabled", o.reasonForWildcard);
    }

    @Test
    void forkModeNeverShortCircuitsToWildcard() {
      MavenProject p = minimalProject("fork-never");
      p.getBuild().addPlugin(surefireWith("forkMode", "never"));
      p.getBuild().flushPluginMap();
      SelectionOutcome o = SelectionEngine.runFor(p, session(), false);
      assertTrue(o.wildcard);
      assertEquals("forkCount=0 / forkMode=never", o.reasonForWildcard);
    }

    @Test
    void forkCountZeroShortCircuitsToWildcard() {
      MavenProject p = minimalProject("fork-zero");
      p.getBuild().addPlugin(surefireWith("forkCount", "0"));
      p.getBuild().flushPluginMap();
      SelectionOutcome o = SelectionEngine.runFor(p, session(), false);
      assertTrue(o.wildcard);
      assertEquals("forkCount=0 / forkMode=never", o.reasonForWildcard);
    }

    @Test
    void forkCountZeroInSurefireExecutionShortCircuitsToWildcard() {
      MavenProject p = minimalProject("fork-zero-exec");
      Plugin sf = surefireWith("forkCount", "1C"); // plugin-level says "fork"
      sf.addExecution(executionWith("default-test", "forkCount", "0")); // execution says "don't"
      p.getBuild().addPlugin(sf);
      p.getBuild().flushPluginMap();
      SelectionOutcome o = SelectionEngine.runFor(p, session(), false);
      assertTrue(o.wildcard, "execution-level forkCount=0 must be honoured, not just plugin-level");
      assertEquals("forkCount=0 / forkMode=never", o.reasonForWildcard);
    }

    @Test
    void cacheRootUnwritableDegradesToWildcard(@TempDir Path tmp) throws IOException {
      Path notADir = Files.writeString(tmp.resolve("blocker"), "x");
      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, notADir.toString());
      MavenSession session = TestSessionFactory.createSession(user, new Properties());
      SelectionOutcome o = SelectionEngine.runFor(minimalProject("cache-bad"), session, false);
      assertTrue(o.wildcard);
      assertTrue(
          o.reasonForWildcard.startsWith("cache root unwritable"), "got: " + o.reasonForWildcard);
    }

    @Test
    void lockUnavailableDegradesToWildcard(@TempDir Path tmp) throws IOException {
      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, tmp.toString());
      MavenSession session = TestSessionFactory.createSession(user, new Properties());
      MavenProject p = minimalProject("lock-busy");
      Path cacheBase = tmp.resolve("io/pjmartos/cull/test").resolve(p.getArtifactId());
      Files.createDirectories(cacheBase);
      try (FileLockHandle held = FileLockHandle.tryAcquire(cacheBase.resolve(".lock"))) {
        assertNotNull(held, "test must own the lock first");
        SelectionOutcome o = SelectionEngine.runFor(p, session, false);
        assertTrue(o.wildcard);
        assertEquals("cache lock unavailable", o.reasonForWildcard);
      }
    }

    @Test
    void stagingDirCreationFailureReleasesLockAndDegrades(@TempDir Path tmp) throws IOException {
      Path blocker = Files.writeString(tmp.resolve("obs-blocker"), "x");
      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
      user.setProperty(CullProperties.OBSERVATIONS_DIR, blocker.toString());
      MavenSession session = TestSessionFactory.createSession(user, new Properties());
      SelectionOutcome o = SelectionEngine.runFor(fullProject(tmp, "staging-bad"), session, false);
      assertTrue(o.wildcard);
      assertTrue(
          o.reasonForWildcard.startsWith("staging dir creation failed"),
          "got: " + o.reasonForWildcard);
      // Lock was released on the failure path, so it can be re-acquired.
      Path cacheBase = tmp.resolve("cache/io/pjmartos/cull/test/staging-bad");
      try (FileLockHandle reacquired = FileLockHandle.tryAcquire(cacheBase.resolve(".lock"))) {
        assertNotNull(reacquired, "lock must have been released on the degradation path");
      }
    }

    @Test
    void bootstrapWithoutCacheRegistersSessionAndSelectsNothingExtra(@TempDir Path tmp)
        throws IOException {
      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
      MavenSession session = TestSessionFactory.createSession(user, new Properties());
      MavenProject p = fullProject(tmp, "bootstrap-empty");

      SelectionOutcome o = SelectionEngine.runFor(p, session, false);
      registeredProject = p;

      assertFalse(o.wildcard, "no test classes -> nothing to collapse");
      assertNotNull(o.sessionId);
      assertTrue(Files.isDirectory(o.stagingDir));
      assertNotNull(CullSessionRegistry.get(p, false), "a CullSession must be registered");
    }

    @Test
    void bootstrapWithNewTestClassesCollapsesToWildcard(@TempDir Path tmp) throws IOException {
      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
      MavenSession session = TestSessionFactory.createSession(user, new Properties());
      MavenProject p = fullProject(tmp, "bootstrap-tests");
      Path testClasses = Path.of(p.getBuild().getTestOutputDirectory());
      Files.createDirectories(testClasses.resolve("com/example"));
      Files.write(testClasses.resolve("com/example/FooTest.class"), new byte[] {(byte) 0xCA});

      SelectionOutcome o = SelectionEngine.runFor(p, session, false);
      registeredProject = p;

      assertTrue(o.wildcard, "every test class is new in bootstrap, so selection collapses to all");
    }

    @Test
    void missingCoverageBaselineForcesWildcardBootstrap(@TempDir Path tmp) throws IOException {
      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
      MavenSession session = TestSessionFactory.createSession(user, new Properties());
      MavenProject p = fullProject(tmp, "cov-bootstrap");
      p.getBuild().addPlugin(jacocoWithPrepareAgent());
      p.getBuild().flushPluginMap();
      knownUnchangedTest(p); // selection would otherwise be an empty subset
      seedPriorGraph(p, session); // graph present, but NO .jacoco.exec baseline

      SelectionOutcome o = SelectionEngine.runFor(p, session, false);
      registeredProject = p;

      assertTrue(o.wildcard, "a missing coverage baseline must force a full bootstrap run");
      assertEquals("coverage baseline bootstrap", o.reasonForWildcard);
    }

    @Test
    void presentCoverageBaselineLeavesSubsetSelectionIntact(@TempDir Path tmp) throws IOException {
      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
      MavenSession session = TestSessionFactory.createSession(user, new Properties());
      MavenProject p = fullProject(tmp, "cov-present");
      p.getBuild().addPlugin(jacocoWithPrepareAgent());
      p.getBuild().flushPluginMap();
      knownUnchangedTest(p);
      String checksum = seedPriorGraph(p, session);
      Path cacheBase = SelectionEngine.cacheBaseFor(p, session);
      Files.write(cacheBase.resolve(checksum + ".jacoco.exec"), new byte[] {1, 2, 3});

      SelectionOutcome o = SelectionEngine.runFor(p, session, false);
      registeredProject = p;

      assertFalse(
          o.wildcard, "with a baseline already captured, an unchanged subset selection stands");
    }

    @Test
    void missingCoverageBaselineWithoutJacocoDoesNotForceWildcard(@TempDir Path tmp)
        throws IOException {
      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
      MavenSession session = TestSessionFactory.createSession(user, new Properties());
      MavenProject p = fullProject(tmp, "no-jacoco");
      knownUnchangedTest(p);
      seedPriorGraph(p, session);

      SelectionOutcome o = SelectionEngine.runFor(p, session, false);
      registeredProject = p;

      assertFalse(o.wildcard, "no JaCoCo configured -> coverage retention is inert, no forced run");
    }

    @Test
    void fallbackRunAllForcesWildcard(@TempDir Path tmp) throws IOException {
      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
      user.setProperty(CullProperties.FALLBACK_RUN_ALL, "true");
      MavenSession session = TestSessionFactory.createSession(user, new Properties());
      MavenProject p = fullProject(tmp, "fallback");

      SelectionOutcome o = SelectionEngine.runFor(p, session, false);
      registeredProject = p;

      assertTrue(o.wildcard, "cull.fallback.runAll forces a full run this invocation");
    }
  }

  /** Opportunistic cache GC: retention bounds, last-used ordering, and mtime fallback. */
  @Nested
  class Gc {

    @Test
    void retentionZeroIsNoop(@TempDir Path cacheBase) throws IOException {
      Path a = writeState(cacheBase, "a", "2026-05-11T10:00:00Z");
      Path b = writeState(cacheBase, "b", "2026-05-11T11:00:00Z");

      SelectionEngine.runOpportunisticGc(cacheBase, 0);

      assertTrue(Files.exists(a));
      assertTrue(Files.exists(b));
    }

    @Test
    void doesNotDeleteWhenWithinRetention(@TempDir Path cacheBase) throws IOException {
      writeState(cacheBase, "a", "2026-05-11T10:00:00Z");
      writeState(cacheBase, "b", "2026-05-11T11:00:00Z");
      writeState(cacheBase, "c", "2026-05-11T12:00:00Z");

      SelectionEngine.runOpportunisticGc(cacheBase, 5);

      assertEquals(3, countStateFiles(cacheBase));
    }

    @Test
    void deletesOldestBeyondRetentionByLastUsed(@TempDir Path cacheBase) throws IOException {
      Path oldest = writeState(cacheBase, "oldest", "2026-05-01T10:00:00Z");
      Path middle = writeState(cacheBase, "middle", "2026-05-05T10:00:00Z");
      Path newer = writeState(cacheBase, "newer", "2026-05-09T10:00:00Z");
      Path newest = writeState(cacheBase, "newest", "2026-05-11T10:00:00Z");

      SelectionEngine.runOpportunisticGc(cacheBase, 2);

      assertTrue(Files.exists(newest));
      assertTrue(Files.exists(newer));
      assertFalse(Files.exists(middle));
      assertFalse(Files.exists(oldest));
      assertFalse(Files.exists(lastUsed(cacheBase, "middle")));
      assertFalse(Files.exists(lastUsed(cacheBase, "oldest")));
      assertTrue(Files.exists(lastUsed(cacheBase, "newest")));
    }

    @Test
    void fallsBackToMtimeWhenLastUsedMissing(@TempDir Path cacheBase) throws IOException {
      Path a = stateFile(cacheBase, "a");
      Path b = stateFile(cacheBase, "b");
      Files.writeString(a, "old");
      Files.writeString(b, "new");
      Files.setLastModifiedTime(a, FileTime.fromMillis(1_000_000));
      Files.setLastModifiedTime(b, FileTime.fromMillis(2_000_000));

      SelectionEngine.runOpportunisticGc(cacheBase, 1);

      assertTrue(Files.exists(b), "newer mtime survives");
      assertFalse(Files.exists(a), "older mtime evicted");
    }
  }

  /** {@link SelectionEngine#cleanupStaleLock} 24h staleness threshold and warning behaviour. */
  @Nested
  class StaleLock {

    @Test
    void noopWhenLockFileMissing(@TempDir Path dir) {
      Path lock = dir.resolve(".lock");
      SelectionEngine.cleanupStaleLock(lock);
      assertFalse(Files.exists(lock));
    }

    @Test
    void preservesFreshLock(@TempDir Path dir) throws IOException {
      Path lock = dir.resolve(".lock");
      Files.writeString(lock, "");
      long oneHourAgo = System.currentTimeMillis() - 60L * 60L * 1000L;
      Files.setLastModifiedTime(lock, FileTime.fromMillis(oneHourAgo));

      SelectionEngine.cleanupStaleLock(lock);

      assertTrue(Files.exists(lock));
    }

    @Test
    void deletesLockOlderThanTwentyFourHours(@TempDir Path dir) throws IOException {
      Path lock = dir.resolve(".lock");
      Files.writeString(lock, "");
      long twentyFiveHoursAgo = System.currentTimeMillis() - 25L * 60L * 60L * 1000L;
      Files.setLastModifiedTime(lock, FileTime.fromMillis(twentyFiveHoursAgo));

      PrintStream originalErr = System.err;
      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
      try {
        SelectionEngine.cleanupStaleLock(lock);
      } finally {
        System.setErr(originalErr);
      }

      assertFalse(Files.exists(lock));
      String warning = captured.toString(StandardCharsets.UTF_8);
      assertTrue(warning.contains("stale lock file"), "expected warning, got: " + warning);
      assertTrue(warning.contains("removing"), "expected removal note, got: " + warning);
    }

    @Test
    void preservesLockJustUnderTwentyFourHours(@TempDir Path dir) throws IOException {
      Path lock = dir.resolve(".lock");
      Files.writeString(lock, "");
      long justUnder24h = System.currentTimeMillis() - (24L * 60L * 60L * 1000L - 5_000L);
      Files.setLastModifiedTime(lock, FileTime.fromMillis(justUnder24h));

      SelectionEngine.cleanupStaleLock(lock);

      assertTrue(Files.exists(lock), "lock just under the 24h threshold must survive");
    }

    @Test
    void nonPositiveThresholdDisablesCleanup(@TempDir Path dir) throws IOException {
      Path lock = dir.resolve(".lock");
      Files.writeString(lock, "");
      long hundredHoursAgo = System.currentTimeMillis() - 100L * 60L * 60L * 1000L;
      Files.setLastModifiedTime(lock, FileTime.fromMillis(hundredHoursAgo));

      SelectionEngine.cleanupStaleLock(lock, 0L);

      assertTrue(Files.exists(lock), "a non-positive threshold opts out of auto-cleanup");
    }

    @Test
    void customThresholdDeletesLockOlderThanConfigured(@TempDir Path dir) throws IOException {
      Path lock = dir.resolve(".lock");
      Files.writeString(lock, "");
      long twoHoursAgo = System.currentTimeMillis() - 2L * 60L * 60L * 1000L;
      Files.setLastModifiedTime(lock, FileTime.fromMillis(twoHoursAgo));

      SelectionEngine.cleanupStaleLock(lock, 60L * 60L * 1000L);

      assertFalse(Files.exists(lock), "older than the configured 1h threshold must be removed");
    }

    @Test
    void customThresholdPreservesLockYoungerThanConfigured(@TempDir Path dir) throws IOException {
      Path lock = dir.resolve(".lock");
      Files.writeString(lock, "");
      long thirtyMinAgo = System.currentTimeMillis() - 30L * 60L * 1000L;
      Files.setLastModifiedTime(lock, FileTime.fromMillis(thirtyMinAgo));

      SelectionEngine.cleanupStaleLock(lock, 60L * 60L * 1000L);

      assertTrue(Files.exists(lock), "younger than the configured threshold must survive");
    }
  }

  // -- hoisted helpers (Java 11: @Nested inner classes cannot declare static members) --

  private static MavenSession session() {
    return TestSessionFactory.createSession(new Properties(), new Properties());
  }

  private static MavenProject minimalProject(String artifact) {
    MavenProject p = new MavenProject();
    p.setGroupId("io.pjmartos.cull.test");
    p.setArtifactId(artifact);
    p.setVersion("1.0");
    p.setBuild(new Build());
    return p;
  }

  private static MavenProject fullProject(Path tmp, String artifact) throws IOException {
    MavenProject p = new MavenProject();
    p.setGroupId("io.pjmartos.cull.test");
    p.setArtifactId(artifact);
    p.setVersion("1.0");
    Path base = Files.createDirectories(tmp.resolve(artifact));
    Path pom = Files.writeString(base.resolve("pom.xml"), "<project/>");
    p.setFile(pom.toFile());
    Build b = new Build();
    Path target = Files.createDirectories(base.resolve("target"));
    b.setDirectory(target.toString());
    b.setOutputDirectory(target.resolve("classes").toString());
    b.setTestOutputDirectory(target.resolve("test-classes").toString());
    p.setBuild(b);
    return p;
  }

  private static Plugin jacocoWithPrepareAgent() {
    Plugin plugin = new Plugin();
    plugin.setGroupId("org.jacoco");
    plugin.setArtifactId("jacoco-maven-plugin");
    plugin.setVersion("0.8.12");
    PluginExecution e = new PluginExecution();
    e.setId("prepare-agent");
    e.setGoals(Collections.singletonList("prepare-agent"));
    plugin.addExecution(e);
    return plugin;
  }

  /** Drop a known test class into test-classes so an unchanged graph selects an empty subset. */
  private static void knownUnchangedTest(MavenProject p) throws IOException {
    Path testClasses = Path.of(p.getBuild().getTestOutputDirectory());
    Files.createDirectories(testClasses.resolve("com/example"));
    Files.write(testClasses.resolve("com/example/FooTest.class"), new byte[] {(byte) 0xCA});
  }

  /**
   * Persist a prior graph that knows {@code com.example.FooTest} with no source files, so the next
   * selection finds nothing changed (an empty subset, not a bootstrap). Returns the cache key.
   */
  private static String seedPriorGraph(MavenProject p, MavenSession session) throws IOException {
    String checksum = ProjectChecksum.compute(p, session, false);
    Path cacheBase = SelectionEngine.cacheBaseFor(p, session);
    Files.createDirectories(cacheBase);
    TestGraph prior =
        new TestGraph(
            Map.of("com.example.FooTest", Set.<RelPath>of()),
            Map.of(),
            Set.of(),
            false,
            Map.of(),
            Map.of(),
            Set.of("com.example.FooTest"));
    Files.write(cacheBase.resolve(checksum + ".state.bin"), TestGraphCodec.encode(prior));
    return checksum;
  }

  private static Plugin surefireWith(String child, String value) {
    Plugin plugin = new Plugin();
    plugin.setGroupId("org.apache.maven.plugins");
    plugin.setArtifactId("maven-surefire-plugin");
    plugin.setVersion("3.2.5");
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom c = new Xpp3Dom(child);
    c.setValue(value);
    cfg.addChild(c);
    plugin.setConfiguration(cfg);
    return plugin;
  }

  private static PluginExecution executionWith(String id, String child, String value) {
    PluginExecution e = new PluginExecution();
    e.setId(id);
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom c = new Xpp3Dom(child);
    c.setValue(value);
    cfg.addChild(c);
    e.setConfiguration(cfg);
    return e;
  }

  private static Path writeState(Path base, String checksum, String lastUsedIso)
      throws IOException {
    Path state = stateFile(base, checksum);
    Files.writeString(state, checksum);
    Files.write(lastUsed(base, checksum), lastUsedIso.getBytes(StandardCharsets.UTF_8));
    return state;
  }

  private static Path stateFile(Path base, String checksum) {
    return base.resolve(checksum + ".state.bin");
  }

  private static Path lastUsed(Path base, String checksum) {
    return base.resolve(checksum + ".last_used");
  }

  private static int countStateFiles(Path base) throws IOException {
    try (java.util.stream.Stream<Path> s = Files.list(base)) {
      return (int) s.filter(p -> p.getFileName().toString().endsWith(".state.bin")).count();
    }
  }
}
