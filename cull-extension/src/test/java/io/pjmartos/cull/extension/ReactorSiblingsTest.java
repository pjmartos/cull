package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.core.CrossRef;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReactorSiblingsTest {

  @Test
  void indexRecordsOwnershipAndHashesOnlyRequestedRefs(@TempDir Path tmp) throws IOException {
    Path modA = Files.createDirectories(tmp.resolve("mod-a/com/example/a"));
    Files.write(modA.resolve("V.class"), new byte[] {1, 2, 3});
    Files.write(modA.resolve("U.class"), new byte[] {4, 5});

    ReactorSiblings.Index idx =
        ReactorSiblings.index(Map.of("com.example:mod-a", tmp.resolve("mod-a")));

    assertTrue(idx.usable());
    assertFalse(idx.ambiguous);
    CrossRef v = new CrossRef("com.example:mod-a", "com.example.a.V");
    CrossRef u = new CrossRef("com.example:mod-a", "com.example.a.U");
    assertTrue(idx.fileByRef.containsKey(v));
    assertTrue(idx.fileByRef.containsKey(u));
    assertEquals("com.example:mod-a", idx.ownerByClass.get("com.example.a.V"));

    Map<CrossRef, byte[]> hashed = ReactorSiblings.hash(idx, Set.of(v));
    assertEquals(1, hashed.size(), "only the requested ref is hashed, not the whole module");
    assertEquals(32, hashed.get(v).length);
    assertFalse(hashed.containsKey(u), "U was not requested so it must not be hashed");
  }

  @Test
  void hashOmitsRefsWithNoFile(@TempDir Path tmp) throws IOException {
    Path modA = Files.createDirectories(tmp.resolve("mod-a/com/example/a"));
    Files.write(modA.resolve("V.class"), new byte[] {1});
    ReactorSiblings.Index idx =
        ReactorSiblings.index(Map.of("com.example:mod-a", tmp.resolve("mod-a")));
    Map<CrossRef, byte[]> hashed =
        ReactorSiblings.hash(idx, Set.of(new CrossRef("com.example:mod-a", "com.example.a.Gone")));
    assertTrue(hashed.isEmpty(), "a vanished/unknown ref is omitted (caller treats it as changed)");
  }

  @Test
  void duplicateFqcnAcrossModulesIsAmbiguousAndUnusable(@TempDir Path tmp) throws IOException {
    Path a = Files.createDirectories(tmp.resolve("a/com/example"));
    Path b = Files.createDirectories(tmp.resolve("b/com/example"));
    Files.write(a.resolve("Dup.class"), new byte[] {1});
    Files.write(b.resolve("Dup.class"), new byte[] {2});

    ReactorSiblings.Index idx =
        ReactorSiblings.index(Map.of("g:a", tmp.resolve("a"), "g:b", tmp.resolve("b")));

    assertTrue(idx.ambiguous, "same FQCN owned by two modules must be flagged");
    assertFalse(idx.usable(), "ambiguity forces the coarse, always-sound path");
  }

  @Test
  void emptyOrMissingIndexIsNotUsable(@TempDir Path tmp) {
    ReactorSiblings.Index idx = ReactorSiblings.index(Map.of("g:a", tmp.resolve("does-not-exist")));
    assertFalse(idx.usable());
    assertFalse(idx.ambiguous);
  }

  @Test
  void resolveMapsReactorDependencyToItsClassesDir(@TempDir Path tmp) throws IOException {
    Path aClasses = Files.createDirectories(tmp.resolve("a/target/classes"));

    MavenProject a = new MavenProject();
    a.setGroupId("com.example");
    a.setArtifactId("mod-a");
    a.setVersion("1.0");
    Build ab = new Build();
    ab.setOutputDirectory(aClasses.toString());
    a.setBuild(ab);

    MavenProject b = new MavenProject();
    b.setGroupId("com.example");
    b.setArtifactId("mod-b");
    b.setVersion("1.0");
    b.setArtifacts(Set.of(artifact("com.example", "mod-a", "1.0", "compile")));

    MavenSession session = session(List.of(a, b));
    Map<String, Path> siblings = ReactorSiblings.resolve(b, session);

    assertEquals(1, siblings.size());
    assertEquals(aClasses, siblings.get("com.example:mod-a"));
    assertTrue(
        ReactorSiblings.isReactorSibling(
            artifact("com.example", "mod-a", "1.0", "compile"), session));
    assertFalse(
        ReactorSiblings.isReactorSibling(artifact("org.third", "lib", "9.9", "compile"), session));
  }

  @Test
  void resolveIgnoresNonReactorAndOutOfScopeDependencies(@TempDir Path tmp) {
    MavenProject b = new MavenProject();
    b.setGroupId("com.example");
    b.setArtifactId("mod-b");
    b.setVersion("1.0");
    b.setArtifacts(Set.of(artifact("org.third", "lib", "1.0", "compile")));
    MavenSession session = session(List.of(b));
    assertTrue(ReactorSiblings.resolve(b, session).isEmpty());
  }

  @Test
  void resolveTreatsNullScopeArtifactAsNonSibling(@TempDir Path tmp) throws IOException {
    Path aClasses = Files.createDirectories(tmp.resolve("a/target/classes"));
    MavenProject a = new MavenProject();
    a.setGroupId("com.example");
    a.setArtifactId("mod-a");
    a.setVersion("1.0");
    Build ab = new Build();
    ab.setOutputDirectory(aClasses.toString());
    a.setBuild(ab);

    MavenProject b = new MavenProject();
    b.setGroupId("com.example");
    b.setArtifactId("mod-b");
    b.setVersion("1.0");
    Artifact nullScoped = artifact("com.example", "mod-a", "1.0", "compile");
    nullScoped.setScope(null);
    b.setArtifacts(Set.of(nullScoped));

    // A null scope is anomalous; it must NOT be treated as a reactor sibling so
    // its content stays in the coarse checksum (conservative: never miss a test).
    assertTrue(ReactorSiblings.resolve(b, session(List.of(a, b))).isEmpty());
  }

  private static Artifact artifact(String g, String a, String v, String scope) {
    return new DefaultArtifact(g, a, v, scope, "jar", null, new DefaultArtifactHandler("jar"));
  }

  private static MavenSession session(List<MavenProject> projects) {
    return TestSessionFactory.createSession(
        new Properties(),
        new Properties(),
        new ArrayList<>(projects),
        new DefaultMavenExecutionResult());
  }
}
