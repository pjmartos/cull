package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.agent.ObservationWriter;
import io.pjmartos.cull.core.CrossRef;
import io.pjmartos.cull.core.FileHasher;
import io.pjmartos.cull.core.RelPath;
import io.pjmartos.cull.core.TestGraph;
import io.pjmartos.cull.core.TestGraphCodec;
import io.pjmartos.cull.extension.crossfixture.StaticRefW;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.maven.execution.BuildFailure;
import org.apache.maven.execution.BuildSuccess;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Model;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link CullSession} — commit, rollback, fromState, allTestsPassed, GC, atomic-move
 * semantics, the end-to-end commit pipeline, XML-reports-disabled handling, and build-failure
 * detection.
 */
class CullSessionTest {

  // -- fromState --

  @Test
  void fromStateCreatesSessionWithoutProject(@TempDir Path tmp) throws IOException {
    Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
    Path staging = Files.createDirectories(tmp.resolve("staging"));
    Path buildDir = Files.createDirectories(tmp.resolve("target"));
    Path classes = Files.createDirectories(buildDir.resolve("classes"));
    Path testClasses = Files.createDirectories(buildDir.resolve("test-classes"));

    Map<String, Set<io.pjmartos.cull.core.RelPath>> emptyDeps = Collections.emptyMap();
    Map<io.pjmartos.cull.core.RelPath, byte[]> emptyHashes = Collections.emptyMap();

    SessionState state =
        new SessionState(
            "abc",
            cacheBase,
            staging,
            tmp.resolve("proj"),
            buildDir,
            classes,
            testClasses,
            5,
            false,
            false,
            false,
            Set.of("FooTest"),
            emptyHashes,
            Set.of());

    CullSession session = CullSession.fromState(state);
    assertEquals("abc", session.projectChecksum());
    assertFalse(session.wildcard());
    assertTrue(session.selectedTests().contains("FooTest"));
  }

  @Test
  void fromStateLoadsPriorGraphWhenStateFileExists(@TempDir Path tmp) throws IOException {
    Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
    Path staging = Files.createDirectories(tmp.resolve("staging"));
    Path buildDir = Files.createDirectories(tmp.resolve("target"));
    Path classes = Files.createDirectories(buildDir.resolve("classes"));
    Path testClasses = Files.createDirectories(buildDir.resolve("test-classes"));

    Map<String, Set<io.pjmartos.cull.core.RelPath>> emptyDeps = Collections.emptyMap();
    Map<io.pjmartos.cull.core.RelPath, byte[]> emptyHashes = Collections.emptyMap();
    Map<io.pjmartos.cull.core.RelPath, byte[]> cacheHashes = Collections.emptyMap();

    // Write a prior graph file
    TestGraph graph = new TestGraph(emptyDeps, cacheHashes, Collections.emptySet(), false);
    byte[] encoded = TestGraphCodec.encode(graph);
    Files.write(cacheBase.resolve("abc.state.bin"), encoded);

    SessionState state =
        new SessionState(
            "abc",
            cacheBase,
            staging,
            tmp.resolve("proj"),
            buildDir,
            classes,
            testClasses,
            5,
            false,
            false,
            false,
            Set.of(),
            emptyHashes,
            Set.of());

    CullSession session = CullSession.fromState(state);
    assertNotNull(session);
  }

  @Test
  void fromStateCrossModuleHandoffCapturesAndPersistsCrossDeps(@TempDir Path tmp)
      throws IOException {
    // Exercises the real cross-classloader path: SessionState carries the
    // reactor-sibling map + mode, fromState reconstructs it via enableCrossModule,
    // and commit attributes a runtime-observed sibling class to a cross dep and
    // persists the upstream snapshot — none of which the registry-path tests cover.
    Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
    Path staging = Files.createDirectories(tmp.resolve("staging"));
    Path buildDir = Files.createDirectories(tmp.resolve("target"));
    Path classes = Files.createDirectories(buildDir.resolve("classes"));
    Path testClasses = Files.createDirectories(buildDir.resolve("test-classes"));
    Path siblingClasses = Files.createDirectories(tmp.resolve("a/com/example/a"));
    Files.write(siblingClasses.resolve("V.class"), new byte[] {1, 2, 3, 4});

    Map<String, String> siblings = new HashMap<>();
    siblings.put("com.example:mod-a", tmp.resolve("a").toString());

    SessionState state =
        new SessionState(
            "csum",
            cacheBase,
            staging,
            tmp.resolve("proj"),
            buildDir,
            classes,
            testClasses,
            5,
            false,
            false,
            false,
            Set.of(),
            Collections.emptyMap(),
            Set.of(),
            siblings,
            "FULL");

    ObservationWriter w = new ObservationWriter(staging.resolve("observations-fork-1.bin"));
    w.writeForkMetadata("fork-1");
    w.recordTestStart("com.example.BvTest");
    w.recordClassLoad("com.example.BvTest", "com.example.a.V");
    w.recordTestEnd("com.example.BvTest");
    w.closeQuietly();

    CullSession.fromState(state).commit();

    TestGraph persisted =
        TestGraphCodec.decode(Files.readAllBytes(cacheBase.resolve("csum.state.bin")));
    CrossRef v = new CrossRef("com.example:mod-a", "com.example.a.V");
    assertTrue(
        persisted.crossDeps().getOrDefault("com.example.BvTest", Set.of()).contains(v),
        "fromState handoff must reconstruct siblings and capture BvTest→mod-a/V");
    assertTrue(
        persisted.upstreamHashes().containsKey(v),
        "fromState handoff must persist the upstream per-class hash snapshot");
  }

  @Test
  void runtimeResourceReadMapsToSrcInsteadOfTargetCopy(@TempDir Path tmp) throws Exception {
    // Spring (and other classpath-scanning frameworks) read resources via
    // ClassLoader.getResourceAsStream("templates/x.html"), which the JVM
    // resolves to target/classes/templates/x.html — the build copy. The
    // underlying truth (the file that changes when the user edits) is
    // src/main/resources/templates/x.html. relativizeResource must prefer
    // the src counterpart so that the dep persisted in state.bin is stable
    // across builds and the test reselects on real edits, not on copy churn.
    Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
    Path staging = Files.createDirectories(tmp.resolve("staging"));
    Path buildDir = Files.createDirectories(tmp.resolve("target"));
    Path classes = Files.createDirectories(buildDir.resolve("classes"));
    Path testClasses = Files.createDirectories(buildDir.resolve("test-classes"));
    Path srcResources = Files.createDirectories(tmp.resolve("src/main/resources"));

    // Both src/ and target/classes/ have the resource — runtime resolves to
    // target, but cull should track src.
    Path srcCopy = srcResources.resolve("templates/foo.html");
    Files.createDirectories(srcCopy.getParent());
    Files.writeString(srcCopy, "<html/>");
    Path tgtCopy = classes.resolve("templates/foo.html");
    Files.createDirectories(tgtCopy.getParent());
    Files.writeString(tgtCopy, "<html/>");
    // A target-only build-generated file (no src counterpart) — must NOT
    // be tracked. The runtime sees target/classes/git.properties but cull
    // refuses to map it back since src/main/resources/git.properties doesn't
    // exist; deps stay free of the timestamp-churning artifact.
    Path generated = classes.resolve("git.properties");
    Files.writeString(generated, "commit.time=now");

    RelPath srcRoot = RelPath.relativize(tmp, srcResources);
    Map<RelPath, byte[]> currentHashes = new HashMap<>();
    RelPath srcCopyRel = RelPath.relativize(tmp, srcCopy);
    currentHashes.put(srcCopyRel, FileHasher.sha256(srcCopy));

    SessionState state =
        new SessionState(
            "csum",
            cacheBase,
            staging,
            tmp,
            buildDir,
            classes,
            testClasses,
            5,
            false,
            false,
            false,
            Set.of(),
            currentHashes,
            Set.of(srcRoot));

    String testId = "com.example.SpringTest";
    ObservationWriter obs = new ObservationWriter(staging.resolve("observations-fork-1.bin"));
    obs.writeForkMetadata("fork-1");
    obs.recordTestStart(testId);
    // Relative classpath resolution path Spring would emit.
    obs.recordResourceRead(testId, "templates/foo.html");
    // Absolute path the JDK Files.newInputStream form would emit.
    obs.recordResourceRead(testId, tgtCopy.toAbsolutePath().toString());
    // Build-generated file: present only in target/. Must be skipped.
    obs.recordResourceRead(testId, "git.properties");
    obs.recordResourceRead(testId, generated.toAbsolutePath().toString());
    obs.recordTestEnd(testId);
    obs.closeQuietly();

    CullSession.fromState(state).commit();

    Path persisted = cacheBase.resolve("csum.state.bin");
    TestGraph g = TestGraphCodec.decode(Files.readAllBytes(persisted));
    Set<RelPath> deps = g.testToDeps().getOrDefault(testId, Set.of());
    assertTrue(
        deps.contains(srcCopyRel),
        "runtime resource read must persist as src/main/resources path, got " + deps);
    for (RelPath d : deps) {
      assertFalse(
          d.value().contains("target/classes/templates/foo.html"),
          "must not persist the target/classes copy: " + d);
      assertFalse(
          d.value().contains("git.properties"),
          "build-generated git.properties must not be tracked: " + d);
    }
  }

  @Test
  void staticClosureCapturesSiblingClassReferencedOnlyInBytecode(@TempDir Path tmp)
      throws Exception {
    // StaticRefW's compiled bytecode references StaticRefV through the constant
    // pool but the test never loads StaticRefV at runtime. The cross dep must
    // still be captured via static-closure-into-siblings ("declared but
    // unloaded"), proving that path independently of the runtime-observed path.
    Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
    Path staging = Files.createDirectories(tmp.resolve("staging"));
    Path buildDir = Files.createDirectories(tmp.resolve("target"));
    Path classes = Files.createDirectories(buildDir.resolve("classes"));
    Path testClasses = Files.createDirectories(buildDir.resolve("test-classes"));
    String pkg = "io/pjmartos/cull/extension/crossfixture";

    // W is downstream-local (in test-classes); V lives only in the sibling.
    copyCompiled("StaticRefW.class", testClasses.resolve(pkg));
    Path siblingClasses = Files.createDirectories(tmp.resolve("sib"));
    copyCompiled("StaticRefV.class", siblingClasses.resolve(pkg));

    Map<String, String> siblings = new HashMap<>();
    siblings.put("io.pjmartos:crossfixture-sib", siblingClasses.toString());

    // W is a downstream-local class, so commit records a RelPath dep on it; that
    // path must be in the hash table or the codec rejects it. basedir = tmp so
    // the relativized path is well-formed.
    Path wFile = testClasses.resolve(pkg).resolve("StaticRefW.class");
    Map<RelPath, byte[]> currentHashes = new HashMap<>();
    currentHashes.put(RelPath.relativize(tmp, wFile), FileHasher.sha256(wFile));

    SessionState state =
        new SessionState(
            "csum",
            cacheBase,
            staging,
            tmp,
            buildDir,
            classes,
            testClasses,
            5,
            false,
            false,
            false,
            Set.of(),
            currentHashes,
            Set.of(),
            siblings,
            "FULL");

    String w = "io.pjmartos.cull.extension.crossfixture.StaticRefW";
    ObservationWriter obs = new ObservationWriter(staging.resolve("observations-fork-1.bin"));
    obs.writeForkMetadata("fork-1");
    obs.recordTestStart(w);
    obs.recordClassLoad(w, w); // only W is loaded at runtime — never StaticRefV
    obs.recordTestEnd(w);
    obs.closeQuietly();

    CullSession.fromState(state).commit();

    TestGraph persisted =
        TestGraphCodec.decode(Files.readAllBytes(cacheBase.resolve("csum.state.bin")));
    CrossRef v =
        new CrossRef(
            "io.pjmartos:crossfixture-sib", "io.pjmartos.cull.extension.crossfixture.StaticRefV");
    assertTrue(
        persisted.crossDeps().getOrDefault(w, Set.of()).contains(v),
        "static closure must attribute StaticRefV (referenced only in W's bytecode, never loaded)");
    assertTrue(persisted.upstreamHashes().containsKey(v));
  }

  private static void copyCompiled(String classFileName, Path destDir) throws Exception {
    Files.createDirectories(destDir);
    Path src = Path.of(StaticRefW.class.getResource(classFileName).toURI());
    Files.copy(src, destDir.resolve(classFileName));
  }

  @Test
  void fromStateRecoversGracefullyWhenStateFileIsCorrupt(@TempDir Path tmp) throws IOException {
    Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
    Path staging = Files.createDirectories(tmp.resolve("staging"));
    Path buildDir = Files.createDirectories(tmp.resolve("target"));
    Path classes = Files.createDirectories(buildDir.resolve("classes"));
    Path testClasses = Files.createDirectories(buildDir.resolve("test-classes"));

    Map<io.pjmartos.cull.core.RelPath, byte[]> emptyHashes = Collections.emptyMap();

    Files.write(cacheBase.resolve("abc.state.bin"), new byte[] {0, 1, 2, 3, 4});

    SessionState state =
        new SessionState(
            "abc",
            cacheBase,
            staging,
            tmp.resolve("proj"),
            buildDir,
            classes,
            testClasses,
            5,
            false,
            false,
            false,
            Set.of(),
            emptyHashes,
            Set.of());

    CullSession session = CullSession.fromState(state);
    assertNotNull(session);
  }

  // -- allTestsPassed / testReportsAllPass --

  @Test
  void testReportsAllPassReturnsFalseForXmlReportsDisabled(@TempDir Path tmp) throws IOException {
    CullSession session = buildSession(tmp, true, Set.of("SomeTest"));
    assertFalse(session.testReportsAllPass());
  }

  @Test
  void testReportsAllPassReturnsTrueForXmlEnabledEmptySelection(@TempDir Path tmp)
      throws IOException {
    CullSession session = buildSession(tmp, false, Set.of());
    assertTrue(session.testReportsAllPass());
  }

  @Test
  void testReportsAllPassReturnsTrueWhenSelectedAreReported(@TempDir Path tmp) throws IOException {
    Path reportsDir = Files.createDirectories(tmp.resolve("proj/target/surefire-reports"));
    writePassingReport(reportsDir, "TEST-com.example.FooTest.xml", "com.example.FooTest");
    writePassingReport(reportsDir, "TEST-com.example.BarTest.xml", "com.example.BarTest");

    CullSession session =
        buildSessionWithReportDir(
            tmp,
            tmp.resolve("proj/target"),
            false,
            Set.of("com.example.FooTest", "com.example.BarTest"));
    assertTrue(session.testReportsAllPass());
  }

  @Test
  void rollbackCleansUpStaging(@TempDir Path tmp) throws IOException {
    Path projDir = Files.createDirectories(tmp.resolve("proj"));
    Path buildDir = Files.createDirectories(projDir.resolve("target"));
    Path mainClasses = Files.createDirectories(buildDir.resolve("classes"));
    Path testClasses = Files.createDirectories(buildDir.resolve("test-classes"));
    Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
    Path staging = Files.createDirectories(tmp.resolve("staging"));

    Files.writeString(staging.resolve("marker.txt"), "test");

    CullSession session =
        new CullSession(
            projDir,
            buildDir,
            mainClasses,
            testClasses,
            cacheBase,
            staging,
            "abc123",
            TestGraph.empty(),
            new HashMap<>(),
            Set.of(),
            Set.of(),
            false,
            false,
            false,
            5);

    session.rollback();
    assertFalse(Files.exists(staging));
  }

  @Test
  void commitMoveOverwritesExistingTarget(@TempDir Path tmp) throws IOException {
    Path source = Files.writeString(tmp.resolve("src.bin"), "new-data");
    Files.writeString(tmp.resolve("dst.bin"), "old-data");
    Path target = tmp.resolve("dst.bin");

    CullSession.commitMove(source, target);
    assertEquals("new-data", Files.readString(target));
    assertFalse(Files.exists(source));
  }

  /** Atomic-move success, overwrite, and non-atomic fallback paths of {@code commitMove}. */
  @Nested
  class CommitMove {

    @Test
    void atomicMoveSucceedsOnNormalFilesystem(@TempDir Path dir) throws IOException {
      Path source = dir.resolve("staging").resolve("new.state.bin");
      Files.createDirectories(source.getParent());
      Files.writeString(source, "payload");
      Path target = dir.resolve("cache").resolve("abc.state.bin");
      Files.createDirectories(target.getParent());

      CullSession.commitMove(source, target);

      assertFalse(Files.exists(source));
      assertTrue(Files.exists(target));
      assertEquals("payload", Files.readString(target));
    }

    @Test
    void atomicMoveOverwritesExistingTarget(@TempDir Path dir) throws IOException {
      Path source = dir.resolve("new.state.bin");
      Files.writeString(source, "new-content");
      Path target = dir.resolve("old.state.bin");
      Files.writeString(target, "old-content");

      CullSession.commitMove(source, target);

      assertFalse(Files.exists(source));
      assertEquals("new-content", Files.readString(target));
    }

    @Test
    void fallsBackToNonAtomicWhenAtomicMoveNotSupported(@TempDir Path dir) throws IOException {
      Path source = dir.resolve("new.state.bin");
      byte[] payload = "fallback-payload".getBytes(StandardCharsets.UTF_8);
      Files.write(source, payload);
      Path target = dir.resolve("target.state.bin");

      AtomicInteger atomicAttempts = new AtomicInteger();
      CullSession.AtomicMover failing =
          (s, t) -> {
            atomicAttempts.incrementAndGet();
            throw new AtomicMoveNotSupportedException(
                s.toString(), t.toString(), "simulated unsupported");
          };

      CullSession.commitMove(source, target, failing);

      assertEquals(1, atomicAttempts.get(), "atomic attempt must be made before fallback");
      assertFalse(Files.exists(source), "source must be moved during fallback");
      assertTrue(Files.exists(target));
      assertArrayEquals(payload, Files.readAllBytes(target));
    }

    @Test
    void fallbackPathReplacesExistingTarget(@TempDir Path dir) throws IOException {
      Path source = dir.resolve("new.state.bin");
      Files.writeString(source, "winner");
      Path target = dir.resolve("target.state.bin");
      Files.writeString(target, "stale");

      CullSession.AtomicMover failing =
          (s, t) -> {
            throw new AtomicMoveNotSupportedException(s.toString(), t.toString(), "x");
          };

      CullSession.commitMove(source, target, failing);

      assertEquals("winner", Files.readString(target));
      assertFalse(Files.exists(source));
    }

    @Test
    void fallbackTriggersForUnsupportedOperationException(@TempDir Path dir) throws IOException {
      Path source = dir.resolve("new.state.bin");
      Files.writeString(source, "data");
      Path target = dir.resolve("target.state.bin");

      CullSession.AtomicMover failing =
          (s, t) -> {
            throw new UnsupportedOperationException("provider lacks ATOMIC_MOVE");
          };

      CullSession.commitMove(source, target, failing);

      assertTrue(Files.exists(target));
      assertEquals("data", Files.readString(target));
    }
  }

  /** End-to-end {@link CullSession#commit()} pipeline: observation merge, mapping, persistence. */
  @Nested
  class CommitPipeline {

    @Test
    void commitMergesRuntimeClassLoadsAndResourcesIntoPersistedGraph(@TempDir Path tmp)
        throws IOException {
      Path proj = Files.createDirectories(tmp.resolve("proj"));
      Path target = Files.createDirectories(proj.resolve("target"));
      Path classes = Files.createDirectories(target.resolve("classes/com/example"));
      Path testClasses = Files.createDirectories(target.resolve("test-classes"));
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      Path staging = Files.createDirectories(tmp.resolve("staging"));

      Path fooClass =
          Files.write(classes.resolve("Foo.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
      Path resDir = Files.createDirectories(proj.resolve("src/test/resources"));
      Path data = Files.writeString(resDir.resolve("data.txt"), "payload");

      RelPath fooRel = RelPath.relativize(proj, fooClass);
      RelPath dataRel = RelPath.relativize(proj, data);
      RelPath resRootRel = RelPath.relativize(proj, resDir);

      // The persisted graph's deps must all be hashed project files, exactly as
      // SelectionEngine's source/class walk would produce in a real run.
      Map<RelPath, byte[]> currentHashes = new HashMap<>();
      currentHashes.put(dataRel, new byte[32]);
      currentHashes.put(fooRel, new byte[32]);

      ByteArrayOutputStream o = new ByteArrayOutputStream();
      forkMeta(o, "fork-1");
      record(o, 5, "com.example.FooTest", "");
      record(o, 0, "com.example.FooTest", "com.example.Foo");
      record(o, 1, "com.example.FooTest", data.toAbsolutePath().toString());
      Files.write(staging.resolve("observations-1-aaaa.bin"), o.toByteArray());

      CullSession cs =
          new CullSession(
              proj,
              target,
              target.resolve("classes"),
              testClasses,
              cacheBase,
              staging,
              "chk",
              TestGraph.empty(),
              currentHashes,
              Set.of("com.example.FooTest"),
              Set.of(resRootRel),
              false,
              false,
              false,
              5);

      cs.commit();

      Path stateFile = cacheBase.resolve("chk.state.bin");
      assertTrue(Files.isRegularFile(stateFile), "commit writes the cache state file");
      TestGraph g = TestGraphCodec.decode(Files.readAllBytes(stateFile));
      Set<RelPath> deps = g.testToDeps().get("com.example.FooTest");
      assertNotNull(deps);
      assertTrue(
          deps.contains(fooRel), "runtime class load maps to its project class file: " + deps);
      assertTrue(
          deps.contains(dataRel), "runtime resource read relativizes to project resource: " + deps);
      assertTrue(Files.isRegularFile(cacheBase.resolve("chk.last_used")));
      assertFalse(Files.exists(staging), "commit removes the staging directory");
    }

    @Test
    void classWindowsScopeDepsSoAnUnrelatedUnitTestIsNotDraggedIn(@TempDir Path tmp)
        throws IOException {
      // Petclinic shape: a Mockito unit test (M) and a Spring test (S) share one
      // reused fork. JUnit discovery loads every test class; S's context loads
      // production class V. With class windows, V must land only in S's deps —
      // changing V must not select M.
      Path proj = Files.createDirectories(tmp.resolve("proj"));
      Path target = Files.createDirectories(proj.resolve("target"));
      Path classes = Files.createDirectories(target.resolve("classes/com/example"));
      Path testClasses = Files.createDirectories(target.resolve("test-classes"));
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      Path staging = Files.createDirectories(tmp.resolve("staging"));

      Path vClass = Files.write(classes.resolve("V.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
      Path mOnly =
          Files.write(classes.resolve("MOnly.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
      RelPath vRel = RelPath.relativize(proj, vClass);
      RelPath mOnlyRel = RelPath.relativize(proj, mOnly);
      Map<RelPath, byte[]> currentHashes = new HashMap<>();
      currentHashes.put(vRel, new byte[32]);
      currentHashes.put(mOnlyRel, new byte[32]);

      ByteArrayOutputStream o = new ByteArrayOutputStream();
      forkMeta(o, "fork-1");
      // discovery phase: launcher loads candidate test classes
      record(o, 4, null, "com.example.SpringTest");
      record(o, 4, null, "com.example.MockitoTest");
      record(o, 8, null, ""); // DISCOVERY_END
      // class window M: only its own production collaborator
      record(o, 9, "com.example.MockitoTest", ""); // CLASS_START
      record(o, 0, "com.example.MockitoTest", "com.example.MOnly");
      record(o, 10, "com.example.MockitoTest", ""); // CLASS_END
      // class window S: Spring context loads V
      record(o, 9, "com.example.SpringTest", "");
      record(o, 0, "com.example.SpringTest", "com.example.V");
      record(o, 10, "com.example.SpringTest", "");
      Files.write(staging.resolve("observations-1-aaaa.bin"), o.toByteArray());

      CullSession cs =
          new CullSession(
              proj,
              target,
              target.resolve("classes"),
              testClasses,
              cacheBase,
              staging,
              "chk",
              TestGraph.empty(),
              currentHashes,
              Set.of("com.example.SpringTest", "com.example.MockitoTest"),
              Set.of(),
              false,
              false,
              false,
              5);

      cs.commit();

      TestGraph g = TestGraphCodec.decode(Files.readAllBytes(cacheBase.resolve("chk.state.bin")));
      Set<RelPath> sDeps = g.testToDeps().get("com.example.SpringTest");
      Set<RelPath> mDeps = g.testToDeps().get("com.example.MockitoTest");
      assertNotNull(sDeps);
      assertNotNull(mDeps);
      assertTrue(sDeps.contains(vRel), "Spring test depends on the context class it loaded");
      assertFalse(
          mDeps.contains(vRel),
          "the Mockito unit test must NOT depend on V — changing V should not select it");
      assertTrue(mDeps.contains(mOnlyRel), "its own collaborator is still tracked");
    }

    @Test
    void commitPersistsDiscoveredButUnexecutedCandidateAsKnownNotAsDep(@TempDir Path tmp)
        throws IOException {
      Path proj = Files.createDirectories(tmp.resolve("proj"));
      Path target = Files.createDirectories(proj.resolve("target"));
      Path classes = Files.createDirectories(target.resolve("classes/com/example"));
      Path testClasses = Files.createDirectories(target.resolve("test-classes"));
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      Path staging = Files.createDirectories(tmp.resolve("staging"));

      Path fooClass =
          Files.write(classes.resolve("Foo.class"), new byte[] {(byte) 0xCA, (byte) 0xFE});
      RelPath fooRel = RelPath.relativize(proj, fooClass);
      Map<RelPath, byte[]> currentHashes = new HashMap<>();
      currentHashes.put(fooRel, new byte[32]);

      // Only FooTest executes and records a class load. MySqlIntegrationTests is
      // an enumerated candidate that never ran (e.g. docker-disabled), so it
      // emits no observations at all.
      ByteArrayOutputStream o = new ByteArrayOutputStream();
      forkMeta(o, "fork-1");
      record(o, 5, "com.example.FooTest", "");
      record(o, 0, "com.example.FooTest", "com.example.Foo");
      Files.write(staging.resolve("observations-1-aaaa.bin"), o.toByteArray());

      CullSession cs =
          new CullSession(
              proj,
              target,
              target.resolve("classes"),
              testClasses,
              cacheBase,
              staging,
              "chk",
              TestGraph.empty(),
              currentHashes,
              Set.of("com.example.FooTest"),
              Set.of("com.example.FooTest", "com.example.MySqlIntegrationTests"),
              Set.of(),
              false,
              false,
              false,
              5);

      cs.commit();

      TestGraph g = TestGraphCodec.decode(Files.readAllBytes(cacheBase.resolve("chk.state.bin")));
      assertTrue(
          g.knownTests().contains("com.example.FooTest"),
          "executed candidate is known: " + g.knownTests());
      assertTrue(
          g.knownTests().contains("com.example.MySqlIntegrationTests"),
          "unexecuted candidate is still persisted as known so it is not re-selected forever: "
              + g.knownTests());
      assertTrue(g.testToDeps().containsKey("com.example.FooTest"));
      assertFalse(
          g.testToDeps().containsKey("com.example.MySqlIntegrationTests"),
          "an unexecuted candidate carries no dependency edge");
    }

    @Test
    void rollbackNeitherWritesCacheNorKeepsStaging(@TempDir Path tmp) throws IOException {
      Path proj = Files.createDirectories(tmp.resolve("proj"));
      Path target = Files.createDirectories(proj.resolve("target"));
      Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
      Path staging = Files.createDirectories(tmp.resolve("staging"));
      Files.writeString(staging.resolve("observations-1-x.bin"), "junk");

      CullSession cs =
          new CullSession(
              proj,
              target,
              target.resolve("classes"),
              target.resolve("test-classes"),
              cacheBase,
              staging,
              "chk",
              TestGraph.empty(),
              new HashMap<>(),
              Set.of(),
              Set.of(),
              false,
              false,
              false,
              5);

      cs.rollback();

      assertFalse(
          Files.exists(cacheBase.resolve("chk.state.bin")), "rollback never writes the cache");
      assertFalse(Files.exists(staging), "rollback removes the staging directory");
    }
  }

  /** Surefire {@code disableXmlReport} handling: commit must be refused without XML reports. */
  @Nested
  class XmlDisabled {

    @Test
    void skipsCommitAndWarnsWhenSurefireXmlReportsAreDisabled(@TempDir Path tmp)
        throws IOException {
      CullSession session =
          buildXmlDisabledSession(tmp, /* xmlReportsDisabled */ true, Set.of("FooTest"));

      PrintStream originalErr = System.err;
      ByteArrayOutputStream captured = new ByteArrayOutputStream();
      System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
      boolean pass;
      try {
        pass = session.testReportsAllPass();
      } finally {
        System.setErr(originalErr);
      }

      assertFalse(pass, "commit must be skipped when XML reports are unavailable");
      String warning = captured.toString(StandardCharsets.UTF_8);
      assertTrue(
          warning.contains("disableXmlReport"),
          "expected warning mentioning disableXmlReport; got: " + warning);
      assertTrue(
          warning.contains("commit skipped"),
          "expected warning to mention skipped commit; got: " + warning);
    }

    @Test
    void emptySelectionWithNoReportsAndXmlDisabledStillSkipsCommit(@TempDir Path tmp)
        throws IOException {
      // Even with an empty selection (would normally short-circuit to "pass"),
      // a true xmlReportsDisabled flag must take precedence and refuse the commit.
      CullSession session = buildXmlDisabledSession(tmp, /* xmlReportsDisabled */ true, Set.of());
      assertFalse(session.testReportsAllPass());
    }

    @Test
    void xmlEnabledAndNoSelectionPassesWithoutReports(@TempDir Path tmp) throws IOException {
      // Sanity: when XML is enabled and the selection is empty, no reports is acceptable.
      CullSession session = buildXmlDisabledSession(tmp, /* xmlReportsDisabled */ false, Set.of());
      assertTrue(session.testReportsAllPass());
    }
  }

  /** {@link CullSession#allTestsPassed} must block commit on any reactor build failure by type. */
  @SuppressWarnings("deprecation")
  @Nested
  class BuildFailureDetection {

    @Test
    void directBuildFailureBlocksCommit(@TempDir Path tmp) throws IOException {
      MavenProject project = minimalProject(tmp);
      CullSession cs = sessionFor(project, tmp);

      DefaultMavenExecutionResult result = new DefaultMavenExecutionResult();
      result.addBuildSummary(new BuildFailure(project, 1L, new RuntimeException("boom")));
      MavenSession session =
          new MavenSession(null, null, new DefaultMavenExecutionRequest(), result);

      assertFalse(cs.allTestsPassed(session));
    }

    @Test
    void subclassedBuildFailureStillBlocksCommit(@TempDir Path tmp) throws IOException {
      MavenProject project = minimalProject(tmp);
      CullSession cs = sessionFor(project, tmp);

      DefaultMavenExecutionResult result = new DefaultMavenExecutionResult();
      // A relocated/proxied BuildFailure has a different simple name; the old
      // getSimpleName()-equals check would miss it and wrongly allow a commit.
      result.addBuildSummary(new RelocatedBuildFailure(project));
      MavenSession session =
          new MavenSession(null, null, new DefaultMavenExecutionRequest(), result);

      assertFalse(cs.allTestsPassed(session));
    }

    @Test
    void buildSuccessDoesNotBlockCommit(@TempDir Path tmp) throws IOException {
      MavenProject project = minimalProject(tmp);
      CullSession cs = sessionFor(project, tmp);

      DefaultMavenExecutionResult result = new DefaultMavenExecutionResult();
      result.addBuildSummary(new BuildSuccess(project, 1L));
      MavenSession session =
          new MavenSession(null, null, new DefaultMavenExecutionRequest(), result);

      // No failure, no exceptions, empty selection, no reports -> commit allowed.
      assertTrue(cs.allTestsPassed(session));
    }
  }

  // -- hoisted helpers (Java 11: @Nested inner classes cannot declare static members) --

  private static CullSession buildSession(Path tmp, boolean xmlReportsDisabled, Set<String> tests)
      throws IOException {
    Path proj = Files.createDirectories(tmp.resolve("proj"));
    Path build = Files.createDirectories(proj.resolve("target"));
    Path mainClasses = Files.createDirectories(build.resolve("classes"));
    Path testClasses = Files.createDirectories(build.resolve("test-classes"));
    Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
    Path staging = Files.createDirectories(tmp.resolve("staging"));

    return new CullSession(
        proj,
        build,
        mainClasses,
        testClasses,
        cacheBase,
        staging,
        "00",
        TestGraph.empty(),
        new HashMap<>(),
        tests,
        Set.of(),
        false,
        false,
        xmlReportsDisabled,
        5);
  }

  private static CullSession buildSessionWithReportDir(
      Path tmp, Path buildDir, boolean xmlReportsDisabled, Set<String> tests) throws IOException {
    Path mainClasses = Files.createDirectories(buildDir.resolve("classes"));
    Path testClasses = Files.createDirectories(buildDir.resolve("test-classes"));
    Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
    Path staging = Files.createDirectories(tmp.resolve("staging"));

    return new CullSession(
        tmp.resolve("proj"),
        buildDir,
        mainClasses,
        testClasses,
        cacheBase,
        staging,
        "00",
        TestGraph.empty(),
        new HashMap<>(),
        tests,
        Set.of(),
        false,
        false,
        xmlReportsDisabled,
        5);
  }

  private static void writePassingReport(Path dir, String filename, String suiteName)
      throws IOException {
    String xml =
        "<?xml version=\"1.0\"?>\n<testsuite name=\""
            + suiteName
            + "\" tests=\"1\" failures=\"0\" errors=\"0\">\n</testsuite>\n";
    Files.writeString(dir.resolve(filename), xml);
  }

  private static CullSession buildXmlDisabledSession(
      Path tmp, boolean xmlReportsDisabled, Set<String> selectedTests) throws IOException {
    Path projectBasedir = Files.createDirectories(tmp.resolve("proj"));
    Path buildDirectory = Files.createDirectories(projectBasedir.resolve("target"));
    Path mainClassesDir = Files.createDirectories(buildDirectory.resolve("classes"));
    Path testClassesDir = Files.createDirectories(buildDirectory.resolve("test-classes"));
    Path cacheBaseDir = Files.createDirectories(tmp.resolve("cache"));
    Path stagingDir = Files.createDirectories(tmp.resolve("staging"));

    return new CullSession(
        projectBasedir,
        buildDirectory,
        mainClassesDir,
        testClassesDir,
        cacheBaseDir,
        stagingDir,
        "00",
        TestGraph.empty(),
        new HashMap<>(),
        selectedTests,
        Set.of(),
        /* wildcard */ false,
        /* degraded */ false,
        xmlReportsDisabled,
        /* retention */ 5);
  }

  private static void forkMeta(ByteArrayOutputStream o, String forkId) {
    o.write(7);
    byte[] f = forkId.getBytes(StandardCharsets.UTF_8);
    writeVarint(o, f.length);
    o.writeBytes(f);
    writeVarint(o, 8);
    for (int i = 0; i < 8; i++) {
      o.write(0);
    }
  }

  private static void record(ByteArrayOutputStream o, int tag, String testId, String payload) {
    o.write(tag);
    if (testId != null) {
      byte[] t = testId.getBytes(StandardCharsets.UTF_8);
      writeVarint(o, t.length);
      o.writeBytes(t);
    }
    byte[] p = payload.getBytes(StandardCharsets.UTF_8);
    writeVarint(o, p.length);
    o.writeBytes(p);
  }

  private static void writeVarint(ByteArrayOutputStream o, int value) {
    int v = value;
    while ((v & ~0x7F) != 0) {
      o.write((v & 0x7F) | 0x80);
      v >>>= 7;
    }
    o.write(v);
  }

  private static MavenProject minimalProject(Path tmp) throws IOException {
    Path projDir = Files.createDirectories(tmp.resolve("proj"));
    Path target = Files.createDirectories(projDir.resolve("target"));
    Path classes = Files.createDirectories(target.resolve("classes"));
    Path testClasses = Files.createDirectories(target.resolve("test-classes"));

    Build build = new Build();
    build.setDirectory(target.toString());
    build.setOutputDirectory(classes.toString());
    build.setTestOutputDirectory(testClasses.toString());

    Model model = new Model();
    model.setBuild(build);

    MavenProject project = new MavenProject(model);
    project.setFile(projDir.resolve("pom.xml").toFile());
    return project;
  }

  private static CullSession sessionFor(MavenProject project, Path tmp) throws IOException {
    Path cacheBase = Files.createDirectories(tmp.resolve("cache"));
    Path staging = Files.createDirectories(tmp.resolve("staging"));
    return new CullSession(
        project,
        cacheBase,
        staging,
        "00",
        TestGraph.empty(),
        new HashMap<>(),
        Set.of(),
        Set.of(),
        false,
        null,
        false,
        5);
  }

  private static final class RelocatedBuildFailure extends BuildFailure {
    RelocatedBuildFailure(MavenProject p) {
      super(p, 1L, new RuntimeException("relocated"));
    }
  }
}
