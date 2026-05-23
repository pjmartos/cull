package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.agent.ObservationWriter;
import io.pjmartos.cull.core.CrossRef;
import io.pjmartos.cull.core.FileHasher;
import io.pjmartos.cull.core.RelPath;
import io.pjmartos.cull.core.TestGraph;
import io.pjmartos.cull.core.TestGraphCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The headline cross-module guarantees, driven through the real {@link SelectionEngine#runFor}: a
 * downstream module reacts to an upstream class change with the right granularity per mode, and
 * degrades to a sound full run when cross-module analysis is not applicable.
 */
class CrossModuleSelectionTest {

  private MavenProject registered;

  @AfterEach
  void releaseSession() {
    if (registered != null) {
      CullSession cs = CullSessionRegistry.remove(registered, false);
      if (cs != null) {
        cs.rollback();
      }
      registered = null;
    }
  }

  @Test
  void fullModeRunsOnlyTheImpactedDownstreamTest(@TempDir Path tmp) throws IOException {
    Fixture f = new Fixture(tmp, "full");
    f.changeUpstreamClass("com/example/a/V.class", new byte[] {9, 9, 9, 9});

    SelectionOutcome o = SelectionEngine.runFor(f.b, f.session, false);
    registered = f.b;

    assertFalse(o.wildcard, "only one test is impacted; not a full run");
    assertEquals(Set.of("com.example.BvTest"), o.selected, "exactly the V-dependent test");
  }

  @Test
  void fullModeIgnoresUpstreamChangeNoDownstreamTestUses(@TempDir Path tmp) throws IOException {
    Fixture f = new Fixture(tmp, "full");
    f.changeUpstreamClass("com/example/a/U.class", new byte[] {7, 7});

    SelectionOutcome o = SelectionEngine.runFor(f.b, f.session, false);
    registered = f.b;

    assertFalse(o.wildcard);
    assertTrue(o.selected.isEmpty(), "no downstream test depends on the changed upstream class");
  }

  @Test
  void nonReactorBuildDegradesToSoundFullRun(@TempDir Path tmp) throws IOException {
    Fixture f = new Fixture(tmp, "full");
    f.changeUpstreamClass("com/example/a/V.class", new byte[] {1, 2});
    // Upstream is not part of the reactor this build → cross-module ineligible →
    // coarse checksum (sibling content included) → key differs from the stored
    // graph → bootstrap → run everything. Sound: never misses a test.
    f.session = f.sessionWithoutUpstream();

    SelectionOutcome o = SelectionEngine.runFor(f.b, f.session, false);
    registered = f.b;

    assertTrue(o.wildcard, "fallback must run all downstream tests rather than risk a miss");
  }

  @Test
  void commitPersistsObservedCrossDepsAndUpstreamSnapshot(@TempDir Path tmp) throws IOException {
    Fixture f = new Fixture(tmp, "full");
    f.changeUpstreamClass("com/example/a/U.class", new byte[] {7, 7}); // irrelevant → selected ∅

    SelectionOutcome o = SelectionEngine.runFor(f.b, f.session, false);
    registered = f.b;
    assertTrue(o.selected.isEmpty(), "precondition: selection empty so commit gate passes");

    ObservationWriter w = new ObservationWriter(o.stagingDir.resolve("observations-fork-1.bin"));
    w.writeForkMetadata("fork-1");
    w.recordTestStart("com.example.BuTest");
    w.recordClassLoad("com.example.BuTest", "com.example.a.U"); // a sibling-owned class
    w.recordTestEnd("com.example.BuTest");
    w.closeQuietly();

    CullSession cs = CullSessionRegistry.get(f.b, false);
    cs.commit();
    registered = null; // commit released the lock and cleaned staging

    String key = ProjectChecksum.compute(f.b, f.session, true);
    Path stateFile =
        f.root.resolve("cache").resolve("com/example").resolve("mod-b").resolve(key + ".state.bin");
    TestGraph persisted = TestGraphCodec.decode(Files.readAllBytes(stateFile));

    CrossRef u = new CrossRef("com.example:mod-a", "com.example.a.U");
    assertTrue(
        persisted.crossDeps().getOrDefault("com.example.BuTest", Set.of()).contains(u),
        "commit must capture the runtime-observed cross-module dependency BuTest→mod-a/U");
    assertTrue(
        persisted.upstreamHashes().containsKey(u),
        "commit must persist the upstream per-class hash snapshot for depended-on classes");
  }

  @Test
  void missingUpstreamOutputDegradesToSoundFullRun(@TempDir Path tmp) throws IOException {
    Fixture f = new Fixture(tmp, "full");
    deleteRecursively(f.root.resolve("a/target/classes"));

    SelectionOutcome o = SelectionEngine.runFor(f.b, f.session, false);
    registered = f.b;

    assertTrue(
        o.wildcard,
        "a reactor sibling not built this session → cross-module ineligible → coarse → full run");
  }

  @Test
  void ambiguousClassOwnershipDegradesToSoundFullRun(@TempDir Path tmp) throws IOException {
    Fixture f = new Fixture(tmp, "full");
    // A second reactor module that also owns com.example.a.V → ambiguous snapshot.
    Path a2Classes = Files.createDirectories(tmp.resolve("a2/target/classes/com/example/a"));
    Files.write(a2Classes.resolve("V.class"), new byte[] {3, 3, 3});
    MavenProject a2 = new MavenProject();
    a2.setGroupId("com.example");
    a2.setArtifactId("mod-a2");
    a2.setVersion("1.0");
    Build a2b = new Build();
    a2b.setOutputDirectory(tmp.resolve("a2/target/classes").toString());
    a2.setBuild(a2b);
    f.b.setArtifacts(
        Set.of(artifact("com.example", "mod-a", "1.0"), artifact("com.example", "mod-a2", "1.0")));
    Properties user = new Properties();
    user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
    user.setProperty(CullProperties.CROSS_MODULE, "full");
    f.session =
        TestSessionFactory.createSession(
            user,
            new Properties(),
            new ArrayList<>(List.of(f.a, a2, f.b)),
            new DefaultMavenExecutionResult());

    SelectionOutcome o = SelectionEngine.runFor(f.b, f.session, false);
    registered = f.b;

    assertTrue(
        o.wildcard, "duplicate FQCN ownership across modules → ambiguous → coarse → full run");
  }

  private static org.apache.maven.artifact.Artifact artifact(String g, String a, String v) {
    return new DefaultArtifact(g, a, v, "compile", "jar", null, new DefaultArtifactHandler("jar"));
  }

  private static void deleteRecursively(Path root) throws IOException {
    if (!Files.exists(root)) {
      return;
    }
    try (Stream<Path> walk = Files.walk(root)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toArray(Path[]::new)) {
        Files.deleteIfExists(p);
      }
    }
  }

  /** Two-module reactor (mod-a → V, U) with a downstream mod-b whose BvTest depends on V. */
  private static final class Fixture {
    final Path root;
    final MavenProject a;
    final MavenProject b;
    final Path upstreamClasses;
    MavenSession session;

    Fixture(Path tmp, String mode) throws IOException {
      root = tmp;
      upstreamClasses = Files.createDirectories(tmp.resolve("a/target/classes/com/example/a"));
      Files.write(upstreamClasses.resolve("V.class"), new byte[] {1, 1, 1});
      Files.write(upstreamClasses.resolve("U.class"), new byte[] {2, 2, 2});

      a = new MavenProject();
      a.setGroupId("com.example");
      a.setArtifactId("mod-a");
      a.setVersion("1.0");
      Build ab = new Build();
      ab.setOutputDirectory(tmp.resolve("a/target/classes").toString());
      a.setBuild(ab);

      Path bDir = Files.createDirectories(tmp.resolve("b"));
      Files.writeString(bDir.resolve("pom.xml"), "<project/>");
      Path bTarget = Files.createDirectories(bDir.resolve("target"));
      Path bTestClasses = Files.createDirectories(bTarget.resolve("test-classes/com/example"));
      Files.write(bTestClasses.resolve("BvTest.class"), new byte[] {10});
      Files.write(bTestClasses.resolve("BuTest.class"), new byte[] {11});

      b = new MavenProject();
      b.setGroupId("com.example");
      b.setArtifactId("mod-b");
      b.setVersion("1.0");
      b.setFile(bDir.resolve("pom.xml").toFile());
      Build bb = new Build();
      bb.setDirectory(bTarget.toString());
      bb.setOutputDirectory(bTarget.resolve("classes").toString());
      bb.setTestOutputDirectory(bTarget.resolve("test-classes").toString());
      b.setBuild(bb);
      // A real backing jar: in a non-reactor build the coarse checksum hashes
      // this file, so the key differs from the content-excluded key and the
      // module bootstraps (sound). A reactor build ignores the file and uses
      // the per-class snapshot instead.
      Path aJar = Files.write(tmp.resolve("mod-a-1.0.jar"), new byte[] {42, 42, 42, 42});
      DefaultArtifact aArtifact =
          new DefaultArtifact(
              "com.example",
              "mod-a",
              "1.0",
              "compile",
              "jar",
              null,
              new DefaultArtifactHandler("jar"));
      aArtifact.setFile(aJar.toFile());
      b.setArtifacts(Set.of(aArtifact));

      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
      user.setProperty(CullProperties.CROSS_MODULE, mode);
      session = session(user, List.of(a, b));

      // Persist a prior v2 graph at the eligible (content-excluded) key: both tests
      // are known with unchanged local hashes; BvTest cross-depends on upstream V,
      // and V's recorded snapshot is its *pre-change* hash.
      Path base = Path.of(b.getBasedir().getAbsolutePath()).normalize();
      RelPath relBv = RelPath.relativize(base, bTestClasses.resolve("BvTest.class"));
      RelPath relBu = RelPath.relativize(base, bTestClasses.resolve("BuTest.class"));
      Map<String, Set<RelPath>> tests = new HashMap<>();
      tests.put("com.example.BvTest", new HashSet<>(Set.of(relBv)));
      tests.put("com.example.BuTest", new HashSet<>(Set.of(relBu)));
      Map<RelPath, byte[]> hashes = new HashMap<>();
      hashes.put(relBv, FileHasher.sha256(bTestClasses.resolve("BvTest.class")));
      hashes.put(relBu, FileHasher.sha256(bTestClasses.resolve("BuTest.class")));
      CrossRef vRef = new CrossRef("com.example:mod-a", "com.example.a.V");
      Map<String, Set<CrossRef>> cross = new HashMap<>();
      cross.put("com.example.BvTest", new HashSet<>(Set.of(vRef)));
      Map<CrossRef, byte[]> upstream = new HashMap<>();
      upstream.put(vRef, FileHasher.sha256(upstreamClasses.resolve("V.class")));

      String key = ProjectChecksum.compute(b, session, true);
      Path cacheBase = tmp.resolve("cache").resolve("com/example").resolve("mod-b");
      Files.createDirectories(cacheBase);
      Files.write(
          cacheBase.resolve(key + ".state.bin"),
          TestGraphCodec.encode(new TestGraph(tests, hashes, Set.of(), false, cross, upstream)));
    }

    void changeUpstreamClass(String relClass, byte[] bytes) throws IOException {
      Files.write(root.resolve("a/target/classes").resolve(relClass), bytes);
    }

    MavenSession sessionWithoutUpstream() {
      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, root.resolve("cache").toString());
      user.setProperty(CullProperties.CROSS_MODULE, "full");
      return session(user, List.of(b));
    }

    private static MavenSession session(Properties user, List<MavenProject> projects) {
      return TestSessionFactory.createSession(
          user, new Properties(), new ArrayList<>(projects), new DefaultMavenExecutionResult());
    }
  }
}
