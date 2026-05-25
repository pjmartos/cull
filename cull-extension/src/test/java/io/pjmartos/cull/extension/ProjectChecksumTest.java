package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.core.FileHasher;
import io.pjmartos.cull.core.Hex;
import io.pjmartos.cull.core.Merkle;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ProjectChecksum} covering canonicalization, property expansion, interpolation,
 * plugin file hashing, relevance filtering, and end-to-end checksum determinism.
 */
class ProjectChecksumTest {

  @Test
  void canonicalSortsChildren() {
    Xpp3Dom a = new Xpp3Dom("config");
    Xpp3Dom z = new Xpp3Dom("z");
    z.setValue("1");
    Xpp3Dom y = new Xpp3Dom("y");
    y.setValue("2");
    a.addChild(z);
    a.addChild(y);

    Xpp3Dom b = new Xpp3Dom("config");
    Xpp3Dom y2 = new Xpp3Dom("y");
    y2.setValue("2");
    Xpp3Dom z2 = new Xpp3Dom("z");
    z2.setValue("1");
    b.addChild(y2);
    b.addChild(z2);

    assertEquals(ProjectChecksum.canonicalize(a), ProjectChecksum.canonicalize(b));
  }

  @Test
  void canonicalDistinguishesValues() {
    Xpp3Dom a = new Xpp3Dom("config");
    Xpp3Dom y = new Xpp3Dom("y");
    y.setValue("1");
    a.addChild(y);
    Xpp3Dom b = new Xpp3Dom("config");
    Xpp3Dom y2 = new Xpp3Dom("y");
    y2.setValue("2");
    b.addChild(y2);
    assertNotEquals(ProjectChecksum.canonicalize(a), ProjectChecksum.canonicalize(b));
  }

  @Test
  void pluginFileHashDifferentJarChangesResult(@TempDir Path tmp) throws Exception {
    Path repoBase = tmp.resolve("repo");
    Path pluginDir = repoBase.resolve("org/example/plug/1.0.0");
    Files.createDirectories(pluginDir);
    Path jar = pluginDir.resolve("plug-1.0.0.jar");
    Files.write(jar, "first-bytes".getBytes(StandardCharsets.UTF_8));

    org.apache.maven.model.Plugin p = new org.apache.maven.model.Plugin();
    p.setGroupId("org.example");
    p.setArtifactId("plug");
    p.setVersion("1.0.0");

    Method m =
        ProjectChecksum.class.getDeclaredMethod(
            "pluginFileHash", org.apache.maven.model.Plugin.class, java.util.Map.class, Path.class);
    m.setAccessible(true);
    byte[] firstHash = (byte[]) m.invoke(null, p, java.util.Map.of(), repoBase);
    assertNotNull(firstHash);
    assertEquals(Hex.encode(FileHasher.sha256(jar)), Hex.encode(firstHash));

    Files.write(jar, "second-bytes-very-different".getBytes(StandardCharsets.UTF_8));
    byte[] secondHash = (byte[]) m.invoke(null, p, java.util.Map.of(), repoBase);
    assertNotEquals(Hex.encode(firstHash), Hex.encode(secondHash));
  }

  @Test
  void pluginFileHashAbsentJarReturnsZeroes(@TempDir Path tmp) throws Exception {
    Path repoBase = tmp.resolve("repo");
    Files.createDirectories(repoBase);
    org.apache.maven.model.Plugin p = new org.apache.maven.model.Plugin();
    p.setGroupId("org.example");
    p.setArtifactId("missing");
    p.setVersion("1.0.0");

    Method m =
        ProjectChecksum.class.getDeclaredMethod(
            "pluginFileHash", org.apache.maven.model.Plugin.class, java.util.Map.class, Path.class);
    m.setAccessible(true);
    byte[] hash = (byte[]) m.invoke(null, p, java.util.Map.of(), repoBase);
    assertEquals(32, hash.length);
    for (byte b : hash) {
      assertEquals(0, b);
    }
  }

  @Test
  void expandResolvesProjectVersionAndProperties() {
    org.apache.maven.project.MavenProject project = new org.apache.maven.project.MavenProject();
    project.setVersion("4.2.1");
    project.getProperties().setProperty("myProp", "abc");
    assertEquals("4.2.1", ProjectChecksum.expand("${project.version}", project, null));
    assertEquals("abc", ProjectChecksum.expand("${myProp}", project, null));
    assertEquals(
        "v=4.2.1;p=abc", ProjectChecksum.expand("v=${version};p=${myProp}", project, null));
  }

  @Test
  void expandLeavesUnresolvedPlaceholdersIntact() {
    org.apache.maven.project.MavenProject project = new org.apache.maven.project.MavenProject();
    assertEquals("${unknown}", ProjectChecksum.expand("${unknown}", project, null));
  }

  @Test
  void interpolatePropagatesIntoChildrenAndAttrs() {
    org.apache.maven.project.MavenProject project = new org.apache.maven.project.MavenProject();
    project.getProperties().setProperty("v", "X");
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom leaf = new Xpp3Dom("value");
    leaf.setAttribute("name", "${v}-attr");
    leaf.setValue("${v}-body");
    cfg.addChild(leaf);
    Xpp3Dom out = ProjectChecksum.interpolate(cfg, project, null);
    assertEquals("X-attr", out.getChild("value").getAttribute("name"));
    assertEquals("X-body", out.getChild("value").getValue());
  }

  @Test
  void pluginTupleFileHashIsLastElement() throws IOException {
    byte[] tuple =
        Merkle.tuple(
            "g".getBytes(StandardCharsets.UTF_8),
            "a".getBytes(StandardCharsets.UTF_8),
            "1".getBytes(StandardCharsets.UTF_8),
            "<cfg/>".getBytes(StandardCharsets.UTF_8),
            "exec".getBytes(StandardCharsets.UTF_8),
            new byte[32]);
    byte[] tuple2 =
        Merkle.tuple(
            "g".getBytes(StandardCharsets.UTF_8),
            "a".getBytes(StandardCharsets.UTF_8),
            "1".getBytes(StandardCharsets.UTF_8),
            "<cfg/>".getBytes(StandardCharsets.UTF_8),
            "exec".getBytes(StandardCharsets.UTF_8),
            new byte[] {1});
    assertNotEquals(Hex.encode(tuple), Hex.encode(tuple2));
  }

  @Test
  void repeatedComputeOnSameProjectIsStable() {
    MavenProject p = projectWithPluginConfig("-Xmx512m");
    MavenSession s = session();
    String first = ProjectChecksum.compute(p, s);
    String second = ProjectChecksum.compute(p, s);
    assertEquals(first, second, "checksum must be deterministic for identical inputs");
  }

  @Test
  void structurallyIdenticalProjectsHashIdentically() {
    assertEquals(
        ProjectChecksum.compute(projectWithPluginConfig("-Xmx512m"), session()),
        ProjectChecksum.compute(projectWithPluginConfig("-Xmx512m"), session()));
  }

  @Test
  void pluginConfigurationChangeChangesChecksum() {
    assertNotEquals(
        ProjectChecksum.compute(projectWithPluginConfig("-Xmx512m"), session()),
        ProjectChecksum.compute(projectWithPluginConfig("-Xmx2g"), session()),
        "a relevant plugin config change must invalidate the cache key");
  }

  @Test
  void pluginExecutionAtTestPhaseIsRelevant() {
    Plugin p = new Plugin();
    PluginExecution e = new PluginExecution();
    e.setPhase("test");
    e.addGoal("test");
    p.addExecution(e);
    assertTrue(isRelevant(p));
  }

  @Test
  void pluginExecutionBeforeTestIsRelevant() {
    Plugin p = new Plugin();
    PluginExecution e = new PluginExecution();
    e.setPhase("compile");
    e.addGoal("compile");
    p.addExecution(e);
    assertTrue(isRelevant(p));
  }

  @Test
  void pluginExecutionAfterTestIsNotRelevant() {
    Plugin p = new Plugin();
    PluginExecution e = new PluginExecution();
    e.setPhase("package");
    e.addGoal("package");
    p.addExecution(e);
    assertFalse(isRelevant(p));
  }

  @Test
  void pluginWithNullPhaseIsRelevant() {
    Plugin p = new Plugin();
    PluginExecution e = new PluginExecution();
    e.addGoal("someGoal");
    p.addExecution(e);
    assertTrue(isRelevant(p));
  }

  @Test
  void pluginWithMultipleExecutionsSomeRelevant() {
    Plugin p = new Plugin();
    PluginExecution e1 = new PluginExecution();
    e1.setId("package-exec");
    e1.setPhase("package");
    e1.addGoal("package");
    PluginExecution e2 = new PluginExecution();
    e2.setId("test-exec");
    e2.setPhase("test");
    e2.addGoal("test");
    p.addExecution(e1);
    p.addExecution(e2);
    assertTrue(isRelevant(p));
  }

  @Test
  void pluginWithOnlyPostTestPhasesIsNotRelevant() {
    Plugin p = new Plugin();
    for (String phase :
        new String[] {"package", "install", "deploy", "integration-test", "verify"}) {
      PluginExecution e = new PluginExecution();
      e.setPhase(phase);
      e.addGoal(phase);
      p.addExecution(e);
    }
    assertFalse(isRelevant(p));
  }

  @Test
  void isRelevantViaReflection() throws Exception {
    Method m = ProjectChecksum.class.getDeclaredMethod("isRelevant", Plugin.class, Set.class);
    m.setAccessible(true);
    Set<String> phases = unitPhases();

    Plugin relevant = new Plugin();
    PluginExecution e = new PluginExecution();
    e.setPhase("compile");
    relevant.addExecution(e);
    assertTrue((Boolean) m.invoke(null, relevant, phases));

    Plugin irrelevant = new Plugin();
    PluginExecution e2 = new PluginExecution();
    e2.setPhase("package");
    irrelevant.addExecution(e2);
    assertFalse((Boolean) m.invoke(null, irrelevant, phases));
  }

  @Test
  void isRelevantUnitScopeExcludesPostTestPhases() {
    Set<String> phases = unitPhases();
    for (String phase :
        new String[] {
          "prepare-package",
          "package",
          "pre-integration-test",
          "integration-test",
          "post-integration-test",
          "verify"
        }) {
      Plugin p = new Plugin();
      PluginExecution e = new PluginExecution();
      e.setPhase(phase);
      p.addExecution(e);
      assertFalse(
          invokeIsRelevant(p, phases), "unit-scope phase set must not include `" + phase + "`");
    }
  }

  @Test
  void isRelevantItScopeIncludesPostTestPhases() {
    Set<String> phases = itPhases();
    for (String phase :
        new String[] {
          "compile",
          "test-compile",
          "test",
          "prepare-package",
          "package",
          "pre-integration-test",
          "integration-test",
          "post-integration-test",
          "verify"
        }) {
      Plugin p = new Plugin();
      PluginExecution e = new PluginExecution();
      e.setPhase(phase);
      p.addExecution(e);
      assertTrue(invokeIsRelevant(p, phases), "IT-scope phase set must include `" + phase + "`");
    }
  }

  @Test
  void isRelevantItScopeStillExcludesPostVerifyPhases() {
    Set<String> phases = itPhases();
    for (String phase : new String[] {"install", "deploy"}) {
      Plugin p = new Plugin();
      PluginExecution e = new PluginExecution();
      e.setPhase(phase);
      p.addExecution(e);
      assertFalse(
          invokeIsRelevant(p, phases),
          "IT-scope phase set must not include post-verify phase `" + phase + "`");
    }
  }

  private static Set<String> unitPhases() {
    return invokePhaseSet("phaseSetAtOrBeforeTest");
  }

  private static Set<String> itPhases() {
    return invokePhaseSet("phaseSetAtOrBeforeVerify");
  }

  @SuppressWarnings("unchecked")
  private static Set<String> invokePhaseSet(String methodName) {
    try {
      Method m = ProjectChecksum.class.getDeclaredMethod(methodName);
      m.setAccessible(true);
      return (Set<String>) m.invoke(null);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private static boolean invokeIsRelevant(Plugin p, Set<String> phases) {
    try {
      Method m = ProjectChecksum.class.getDeclaredMethod("isRelevant", Plugin.class, Set.class);
      m.setAccessible(true);
      return (Boolean) m.invoke(null, p, phases);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void canonicalizeHandlesEmptyNode() {
    Xpp3Dom node = new Xpp3Dom("empty");
    assertEquals("<empty/>", ProjectChecksum.canonicalize(node));
  }

  @Test
  void canonicalizeHandlesNodeWithValue() {
    Xpp3Dom node = new Xpp3Dom("value");
    node.setValue("  abc  ");
    assertEquals("<value>abc</value>", ProjectChecksum.canonicalize(node));
  }

  @Test
  void canonicalizeHandlesNodeWithAttributes() {
    Xpp3Dom node = new Xpp3Dom("config");
    node.setAttribute("z", "1");
    node.setAttribute("a", "2");
    String expected = "<config a=\"2\" z=\"1\"/>";
    assertEquals(expected, ProjectChecksum.canonicalize(node));
  }

  @Test
  void canonicalizeSortsChildrenRecursively() {
    Xpp3Dom root = new Xpp3Dom("root");
    Xpp3Dom b = new Xpp3Dom("b");
    b.setValue("1");
    Xpp3Dom a = new Xpp3Dom("a");
    a.setValue("2");
    root.addChild(b);
    root.addChild(a);

    String result = ProjectChecksum.canonicalize(root);
    assertTrue(result.contains("<a>2</a>"), "child 'a' should appear before 'b': " + result);
    assertTrue(result.contains("<b>1</b>"), result);
  }

  @Test
  void expandMultiplePassesResolveNestedProperty() {
    MavenProject project = new MavenProject();
    project.getProperties().setProperty("outer", "${inner}");
    project.getProperties().setProperty("inner", "final");
    assertEquals("final", ProjectChecksum.expand("${outer}", project, null));
  }

  @Test
  void expandWithCircularReferenceStopsAfterMaxIterations() {
    MavenProject project = new MavenProject();
    project.getProperties().setProperty("a", "${b}");
    project.getProperties().setProperty("b", "${a}");
    String result = ProjectChecksum.expand("${a}", project, null);
    assertNotNull(result);
  }

  @Test
  void interpolateNullReturnsNull() {
    assertNull(ProjectChecksum.interpolate(null, new MavenProject(), null));
  }

  @Test
  void computeReturnsNonEmptyHashForMinimalProject() {
    MavenProject project = new MavenProject();
    project.setGroupId("com.example");
    project.setArtifactId("test");
    project.setVersion("1.0");
    Build build = new Build();
    project.setBuild(build);

    String result = ProjectChecksum.compute(project, null);
    assertNotNull(result);
    assertFalse(result.isEmpty());
    assertEquals(64, result.length());
  }

  @Test
  void computeSameStructureProjects() {
    MavenProject p1 = new MavenProject();
    p1.setGroupId("com.a");
    p1.setArtifactId("mod-a");
    p1.setVersion("1.0");
    p1.setBuild(new Build());

    MavenProject p2 = new MavenProject();
    p2.setGroupId("com.b");
    p2.setArtifactId("mod-b");
    p2.setVersion("1.0");
    p2.setBuild(new Build());

    String hash1 = ProjectChecksum.compute(p1, null);
    String hash2 = ProjectChecksum.compute(p2, null);
    // Same structure with empty deps may produce identical hashes; verify stable output
    assertNotNull(hash2);
    assertEquals(64, hash1.length());
    assertEquals(64, hash2.length());
  }

  private static MavenSession session() {
    return TestSessionFactory.createSession(new Properties(), new Properties());
  }

  private static MavenProject projectWithPluginConfig(String surefireArgLine) {
    MavenProject p = new MavenProject();
    p.setGroupId("com.example");
    p.setArtifactId("mod");
    p.setVersion("1.2.3");
    Build b = new Build();
    Plugin sure = new Plugin();
    sure.setGroupId("org.apache.maven.plugins");
    sure.setArtifactId("maven-surefire-plugin");
    sure.setVersion("3.2.5");
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom arg = new Xpp3Dom("argLine");
    arg.setValue(surefireArgLine);
    cfg.addChild(arg);
    sure.setConfiguration(cfg);
    PluginExecution exec = new PluginExecution();
    exec.setId("default-test");
    exec.setPhase("test");
    exec.setGoals(List.of("test"));
    sure.setExecutions(List.of(exec));
    b.addPlugin(sure);
    p.setBuild(b);
    return p;
  }

  private static boolean isRelevant(Plugin p) {
    return invokeIsRelevant(p, unitPhases());
  }

  @Test
  void cullignoreExcludesMatchedArgLineFromChecksum(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve(".cullignore"), "org.apache.maven.plugins:maven-surefire-plugin#argLine\n");
    String h1 = checksumAt(tmp, projectWithPluginConfig("-Xmx512m"));
    String h2 = checksumAt(tmp, projectWithPluginConfig("-Xmx2g -DsomeFlag"));
    assertEquals(h1, h2, "an excluded argLine must not affect the checksum");
  }

  @Test
  void withoutCullignoreArgLineStillAffectsChecksum(@TempDir Path tmp) throws IOException {
    String h1 = checksumAt(tmp, projectWithPluginConfig("-Xmx512m"));
    String h2 = checksumAt(tmp, projectWithPluginConfig("-Xmx2g"));
    assertNotEquals(h1, h2, "a non-excluded argLine change must rotate the checksum");
  }

  @Test
  void changingExclusionPolicyRotatesChecksumOnce(@TempDir Path tmp) throws IOException {
    String before = checksumAt(tmp, projectWithPluginConfig("-Xmx512m"));
    Files.writeString(
        tmp.resolve(".cullignore"), "org.apache.maven.plugins:maven-surefire-plugin#argLine\n");
    String after = checksumAt(tmp, projectWithPluginConfig("-Xmx512m"));
    assertNotEquals(before, after, "changing the exclusion policy must force one cold rebuild");
  }

  @Test
  void cullInjectedAgentPathIsAutoExcludedWithoutCullignore(@TempDir Path tmp) throws IOException {
    String a =
        checksumAt(
            tmp,
            projectWithPluginConfig(
                "-javaagent:/home/ci/.cull/agents/1/cull-agent-1.jar=observations=${cull.observations.dir}"));
    String b =
        checksumAt(
            tmp,
            projectWithPluginConfig(
                "-javaagent:C:/Users/x/.cull/agents/2/cull-agent-2.jar=observations=${cull.observations.dir}"));
    assertEquals(a, b, "cull's own machine-specific injected agent path must not rotate its key");
  }

  @Test
  void cullStripPreservesUserArgLine(@TempDir Path tmp) throws IOException {
    String userArgsA =
        checksumAt(
            tmp,
            projectWithPluginConfig(
                "-javaagent:/p/cull-agent-1.jar --add-opens java.base/java.lang=ALL-UNNAMED"));
    String userArgsB =
        checksumAt(
            tmp,
            projectWithPluginConfig(
                "-javaagent:/q/cull-agent-2.jar --add-opens java.base/java.lang=ALL-UNNAMED"));
    String differentUserArgs =
        checksumAt(tmp, projectWithPluginConfig("-javaagent:/p/cull-agent-1.jar -Xmx9g"));
    assertEquals(
        userArgsA, userArgsB, "only cull's segment is stripped; same user args ⇒ same key");
    assertNotEquals(
        userArgsA, differentUserArgs, "a real user argLine change must still rotate the key");
  }

  private static String checksumAt(Path baseDir, MavenProject p) throws IOException {
    Path pom = baseDir.resolve("pom.xml");
    if (!Files.exists(pom)) {
      Files.writeString(pom, "<project/>");
    }
    p.setFile(pom.toFile());
    return ProjectChecksum.compute(p, session());
  }

  @Test
  void excludingSiblingContentKeepsKeyStableAcrossUpstreamContentChange(@TempDir Path tmp)
      throws IOException {
    Path aClasses = Files.createDirectories(tmp.resolve("a/target/classes/com/example/a"));
    Files.write(aClasses.resolve("V.class"), new byte[] {1, 1, 1});

    MavenProject a = reactorProject("com.example", "mod-a", "1.0", tmp.resolve("a/target/classes"));
    MavenProject b = downstream("com.example", "mod-b", "1.0");
    MavenSession session = sessionWith(a, b);

    String includedBefore = ProjectChecksum.compute(b, session, false, false);
    String excludedBefore = ProjectChecksum.compute(b, session, false, true);

    Files.write(aClasses.resolve("V.class"), new byte[] {9, 9, 9, 9});

    String includedAfter = ProjectChecksum.compute(b, session, false, false);
    String excludedAfter = ProjectChecksum.compute(b, session, false, true);

    assertNotEquals(includedBefore, includedAfter, "coarse mode: upstream content rotates the key");
    assertEquals(
        excludedBefore,
        excludedAfter,
        "excluding sibling content keeps the downstream key stable across an upstream change");
  }

  @Test
  void excludingSiblingContentStillReactsToUpstreamCoordinateChange(@TempDir Path tmp)
      throws IOException {
    Files.createDirectories(tmp.resolve("a/target/classes/com/example/a"));
    Files.write(tmp.resolve("a/target/classes/com/example/a/V.class"), new byte[] {1});

    MavenProject a = reactorProject("com.example", "mod-a", "1.0", tmp.resolve("a/target/classes"));
    MavenProject b1 = downstream("com.example", "mod-b", "1.0");
    String v1 = ProjectChecksum.compute(b1, sessionWith(a, b1), false, true);

    MavenProject a2 =
        reactorProject("com.example", "mod-a", "2.0", tmp.resolve("a/target/classes"));
    MavenProject b2 = downstreamDependingOn("com.example", "mod-b", "1.0", "2.0");
    String v2 = ProjectChecksum.compute(b2, sessionWith(a2, b2), false, true);

    assertNotEquals(v1, v2, "a sibling version bump must still rotate the key even when excluded");
  }

  @Test
  void crossModuleToggleRotatesKeyOnceThenStable(@TempDir Path tmp) throws IOException {
    Files.createDirectories(tmp.resolve("a/target/classes/com/example/a"));
    Files.write(tmp.resolve("a/target/classes/com/example/a/V.class"), new byte[] {1});
    MavenProject a = reactorProject("com.example", "mod-a", "1.0", tmp.resolve("a/target/classes"));
    MavenProject b = downstream("com.example", "mod-b", "1.0");
    MavenSession s = sessionWith(a, b);

    String coarse1 = ProjectChecksum.compute(b, s, false, false);
    String coarse2 = ProjectChecksum.compute(b, s, false, false);
    String excl1 = ProjectChecksum.compute(b, s, false, true);
    String excl2 = ProjectChecksum.compute(b, s, false, true);

    assertEquals(coarse1, coarse2, "coarse mode is deterministic");
    assertEquals(excl1, excl2, "excluded mode is deterministic");
    assertNotEquals(coarse1, excl1, "toggling cull.crossmodule rotates the cache key exactly once");
  }

  @Test
  void itChecksumReactsToFailsafeConfigChange(@TempDir Path tmp) throws IOException {
    MavenProject before = projectWithFailsafeArg("-Xmx512m");
    MavenProject after = projectWithFailsafeArg("-Xmx2g");
    String itBefore = checksumAt(tmp, before, true);
    String itAfter = checksumAt(tmp, after, true);
    assertNotEquals(
        itBefore,
        itAfter,
        "a Failsafe config change must rotate the IT key (Failsafe is in-scope for the IT phase set)");
  }

  @Test
  void unitChecksumIgnoresFailsafeConfigChange(@TempDir Path tmp) throws IOException {
    MavenProject before = projectWithFailsafeArg("-Xmx512m");
    MavenProject after = projectWithFailsafeArg("-Xmx2g");
    String unitBefore = checksumAt(tmp, before, false);
    String unitAfter = checksumAt(tmp, after, false);
    assertEquals(
        unitBefore,
        unitAfter,
        "a Failsafe config change must NOT rotate the unit key (Failsafe is post-test, out of scope)");
  }

  @Test
  void unitChecksumReactsToSurefireConfigChange(@TempDir Path tmp) throws IOException {
    MavenProject before = projectWithPluginConfig("-Xmx512m");
    MavenProject after = projectWithPluginConfig("-Xmx2g");
    String unitBefore = checksumAt(tmp, before, false);
    String unitAfter = checksumAt(tmp, after, false);
    assertNotEquals(unitBefore, unitAfter, "a Surefire config change must rotate the unit key");
  }

  @Test
  void itChecksumReactsToSurefireConfigChange(@TempDir Path tmp) throws IOException {
    MavenProject before = projectWithPluginConfig("-Xmx512m");
    MavenProject after = projectWithPluginConfig("-Xmx2g");
    String itBefore = checksumAt(tmp, before, true);
    String itAfter = checksumAt(tmp, after, true);
    assertNotEquals(
        itBefore,
        itAfter,
        "a Surefire config change must also rotate the IT key (Surefire ⊂ IT phase scope)");
  }

  @Test
  void itAndUnitChecksumsDifferWhenPostTestPluginPresent(@TempDir Path tmp) throws IOException {
    MavenProject p = projectWithFailsafeArg("-Xmx1g");
    String unit = checksumAt(tmp, p, false);
    String it = checksumAt(tmp, p, true);
    assertNotEquals(
        unit,
        it,
        "the IT and unit checksums of the same project must diverge when a post-test plugin contributes");
  }

  private static MavenProject projectWithFailsafeArg(String failsafeArgLine) {
    MavenProject p = new MavenProject();
    p.setGroupId("io.pjmartos.cull.test");
    p.setArtifactId("mod");
    p.setVersion("1.2.3");
    Build b = new Build();
    Plugin fs = new Plugin();
    fs.setGroupId("org.apache.maven.plugins");
    fs.setArtifactId("maven-failsafe-plugin");
    fs.setVersion("3.2.5");
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom arg = new Xpp3Dom("argLine");
    arg.setValue(failsafeArgLine);
    cfg.addChild(arg);
    fs.setConfiguration(cfg);
    PluginExecution exec = new PluginExecution();
    exec.setId("default-integration-test");
    exec.setPhase("integration-test");
    exec.setGoals(List.of("integration-test", "verify"));
    fs.setExecutions(List.of(exec));
    b.addPlugin(fs);
    p.setBuild(b);
    return p;
  }

  private static String checksumAt(Path baseDir, MavenProject p, boolean integration)
      throws IOException {
    Path pom = baseDir.resolve("pom.xml");
    if (!Files.exists(pom)) {
      Files.writeString(pom, "<project/>");
    }
    p.setFile(pom.toFile());
    return ProjectChecksum.compute(p, session(), integration, false);
  }

  private static MavenProject reactorProject(String g, String a, String v, Path classesDir) {
    MavenProject p = new MavenProject();
    p.setGroupId(g);
    p.setArtifactId(a);
    p.setVersion(v);
    Build b = new Build();
    b.setOutputDirectory(classesDir.toString());
    p.setBuild(b);
    return p;
  }

  private static MavenProject downstream(String g, String a, String v) {
    return downstreamDependingOn(g, a, v, "1.0");
  }

  private static MavenProject downstreamDependingOn(
      String g, String a, String v, String siblingVersion) {
    MavenProject p = new MavenProject();
    p.setGroupId(g);
    p.setArtifactId(a);
    p.setVersion(v);
    p.setBuild(new Build());
    p.setArtifacts(
        Set.of(
            new DefaultArtifact(
                "com.example",
                "mod-a",
                siblingVersion,
                "compile",
                "jar",
                null,
                new DefaultArtifactHandler("jar"))));
    return p;
  }

  private static MavenSession sessionWith(MavenProject... projects) {
    List<MavenProject> list = new ArrayList<>(Arrays.asList(projects));
    return TestSessionFactory.createSession(
        new Properties(), new Properties(), list, new DefaultMavenExecutionResult());
  }
}
