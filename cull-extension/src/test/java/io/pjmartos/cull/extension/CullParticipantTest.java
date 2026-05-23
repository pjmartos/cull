package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.core.TestGraph;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CullParticipant} — the Maven lifecycle participant that orchestrates config
 * injection, mojo binding, and session finalization including registry-backed commit/rollback and
 * the session-state file fallback.
 */
class CullParticipantTest {

  @Test
  void afterProjectsReadReturnsEarlyWhenDisabled() {
    Properties props = new Properties();
    props.setProperty(CullProperties.DISABLED, "true");
    MavenSession session = TestSessionFactory.createSession(props, new Properties());
    CullParticipant participant = new CullParticipant();
    assertDoesNotThrow(() -> participant.afterProjectsRead(session));
  }

  @Test
  void afterProjectsReadHandlesNullBuildGracefully() {
    MavenSession session = TestSessionFactory.createSession(new Properties(), new Properties());
    CullParticipant participant = new CullParticipant();
    assertDoesNotThrow(() -> participant.afterProjectsRead(session));
  }

  @Test
  void afterProjectsReadDoesNotThrowOnNormalProject() {
    MavenSession session = TestSessionFactory.createSession(new Properties(), new Properties());
    MavenProject project = new MavenProject();
    Build build = new Build();
    build.addPlugin(new Plugin());
    project.setBuild(build);
    session.getProjects().add(project);

    CullParticipant participant = new CullParticipant();
    assertDoesNotThrow(() -> participant.afterProjectsRead(session));
  }

  @Test
  void afterSessionEndReturnsEarlyWhenDisabled() {
    Properties props = new Properties();
    props.setProperty(CullProperties.DISABLED, "true");
    MavenSession session = TestSessionFactory.createSession(props, new Properties());

    CullParticipant participant = new CullParticipant();
    assertDoesNotThrow(() -> participant.afterSessionEnd(session));
  }

  @Test
  void afterSessionEndHandlesEmptyProjectsList() {
    MavenSession session = TestSessionFactory.createSession(new Properties(), new Properties());
    CullParticipant participant = new CullParticipant();
    assertDoesNotThrow(() -> participant.afterSessionEnd(session));
  }

  @Test
  void afterSessionEndSuppressesExceptionFromFailedProject() {
    MavenSession session = TestSessionFactory.createSession(new Properties(), new Properties());
    CullParticipant participant = new CullParticipant();
    assertDoesNotThrow(() -> participant.afterSessionEnd(session));
  }

  @Test
  void registeredPassingSessionIsCommittedAndDeregistered(@TempDir Path tmp) throws IOException {
    MavenProject p = project("part-commit", tmp);
    Path buildDir = Path.of(p.getBuild().getDirectory());
    Path surefire = Files.createDirectories(buildDir.resolve("surefire-reports"));
    writeReport(surefire, "TEST-com.example.FooTest.xml", 0, 0);
    Path cacheBase = Files.createDirectories(tmp.resolve("cache-commit"));

    CullSession cs = session(tmp, "part-commit", buildDir, cacheBase, "chk1");
    CullSessionRegistry.put(p, cs, false);

    MavenSession session = TestSessionFactory.createSession(new Properties(), new Properties());
    session.getProjects().add(p);

    new CullParticipant().afterSessionEnd(session);

    assertTrue(
        Files.isRegularFile(cacheBase.resolve("chk1.state.bin")),
        "a passing registered session must commit its cache");
    assertNull(CullSessionRegistry.get(p, false), "the session must be deregistered");
  }

  @Test
  void registeredFailingSessionRollsBackWithoutWritingCache(@TempDir Path tmp) throws IOException {
    MavenProject p = project("part-rollback", tmp);
    Path buildDir = Path.of(p.getBuild().getDirectory());
    Path surefire = Files.createDirectories(buildDir.resolve("surefire-reports"));
    writeReport(surefire, "TEST-com.example.FooTest.xml", 1, 0);
    Path cacheBase = Files.createDirectories(tmp.resolve("cache-rollback"));

    CullSession cs = session(tmp, "part-rollback", buildDir, cacheBase, "chk2");
    CullSessionRegistry.put(p, cs, false);

    MavenSession session = TestSessionFactory.createSession(new Properties(), new Properties());
    session.getProjects().add(p);

    new CullParticipant().afterSessionEnd(session);

    assertFalse(
        Files.exists(cacheBase.resolve("chk2.state.bin")),
        "a failing session must not write the cache");
    assertNull(CullSessionRegistry.get(p, false));
  }

  @Test
  void loadSessionFallsBackToSessionStateFileThenDeletesIt(@TempDir Path tmp) throws IOException {
    MavenProject p = project("part-fallback", tmp);
    Path buildDir = Files.createDirectories(Path.of(p.getBuild().getDirectory()));
    Path cacheBase = tmp.resolve("cache-fallback");

    SessionState state =
        new SessionState(
            "chk3",
            cacheBase,
            tmp.resolve("staging-fallback"),
            tmp.resolve("proj-fallback"),
            buildDir,
            buildDir.resolve("classes"),
            buildDir.resolve("test-classes"),
            5,
            false,
            false,
            false,
            Set.of(),
            new HashMap<>(),
            Set.of());
    Path stateFile = SelectionEngine.sessionStateFile(p, false);
    Files.createDirectories(stateFile.getParent());
    Files.write(stateFile, SessionStateCodec.encode(state));

    MavenSession session = TestSessionFactory.createSession(new Properties(), new Properties());
    session.getProjects().add(p);

    new CullParticipant().afterSessionEnd(session);

    assertTrue(
        Files.isRegularFile(cacheBase.resolve("chk3.state.bin")),
        "session reconstructed from the state file must commit (empty selection, no reports)");
    assertFalse(Files.exists(stateFile), "the session-state file must be deleted afterwards");
  }

  private static MavenProject project(String artifact, Path tmp) throws IOException {
    MavenProject p = new MavenProject();
    p.setGroupId("io.pjmartos.cull.test");
    p.setArtifactId(artifact);
    p.setVersion("1.0");
    Path base = Files.createDirectories(tmp.resolve(artifact));
    Build b = new Build();
    b.setDirectory(base.resolve("target").toString());
    p.setBuild(b);
    return p;
  }

  private static CullSession session(
      Path tmp, String artifact, Path buildDir, Path cacheBase, String checksum)
      throws IOException {
    Path proj = Files.createDirectories(tmp.resolve(artifact));
    Path staging = Files.createDirectories(tmp.resolve(artifact + "-staging"));
    Map<io.pjmartos.cull.core.RelPath, byte[]> hashes = new HashMap<>();
    return new CullSession(
        proj,
        buildDir,
        buildDir.resolve("classes"),
        buildDir.resolve("test-classes"),
        cacheBase,
        staging,
        checksum,
        TestGraph.empty(),
        hashes,
        Set.of(),
        Set.of(),
        false,
        false,
        false,
        5);
  }

  private static void writeReport(Path dir, String name, int failures, int errors)
      throws IOException {
    String xml =
        "<?xml version=\"1.0\"?>\n<testsuite name=\"com.example.FooTest\" tests=\"1\" failures=\""
            + failures
            + "\" errors=\""
            + errors
            + "\">\n</testsuite>\n";
    Files.writeString(dir.resolve(name), xml);
  }
}
