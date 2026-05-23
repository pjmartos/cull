package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.pjmartos.cull.core.TestGraph;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Tests for {@link CullSessionRegistry} — the concurrent map that holds in-memory sessions. */
class CullSessionRegistryTest {

  private final MavenProject project = project("com.example", "my-app", "1.0");

  @AfterEach
  void tearDown() {
    CullSessionRegistry.remove(project, false);
    CullSessionRegistry.remove(project, true);
    CullSessionRegistry.remove(project("other", "project", "1.0"), false);
  }

  @Test
  void putAndGetRoundtrip() {
    CullSession s = session("chk1");
    CullSessionRegistry.put(project, s, false);
    assertNotNull(CullSessionRegistry.get(project, false));
  }

  @Test
  void getReturnsNullForUnknownKey() {
    assertNull(CullSessionRegistry.get(project, false));
  }

  @Test
  void unitAndIntegrationAreSeparate() {
    CullSession utSession = session("ut-chk");
    CullSession itSession = session("it-chk");
    CullSessionRegistry.put(project, utSession, false);
    CullSessionRegistry.put(project, itSession, true);

    assertEquals("ut-chk", CullSessionRegistry.get(project, false).projectChecksum());
    assertEquals("it-chk", CullSessionRegistry.get(project, true).projectChecksum());
  }

  @Test
  void removeReturnsAndClears() {
    CullSession s = session("to-remove");
    CullSessionRegistry.put(project, s, false);

    CullSession removed = CullSessionRegistry.remove(project, false);
    assertNotNull(removed);
    assertEquals("to-remove", removed.projectChecksum());
    assertNull(CullSessionRegistry.get(project, false));
  }

  @Test
  void removeReturnsNullForMissing() {
    assertNull(CullSessionRegistry.remove(project, false));
  }

  @Test
  void putOverwritesExistingEntry() {
    CullSession s1 = session("first");
    CullSession s2 = session("second");
    CullSessionRegistry.put(project, s1, false);
    CullSessionRegistry.put(project, s2, false);
    assertEquals("second", CullSessionRegistry.get(project, false).projectChecksum());
  }

  @Test
  void keyFormatContainsAllComponents() {
    String k = CullSessionRegistry.key(project("g", "a", "v"), false);
    assertEquals("g:a:v:ut", k);
  }

  @Test
  void keyFormatWithIntegrationSuffix() {
    String k = CullSessionRegistry.key(project("com.example", "my-app", "1.0"), true);
    assertEquals("com.example:my-app:1.0:it", k);
  }

  @Test
  void twoDifferentProjectsHaveDifferentKeys() {
    String k1 = CullSessionRegistry.key(project("com.a", "mod-a", "1.0"), false);
    String k2 = CullSessionRegistry.key(project("com.b", "mod-b", "1.0"), false);
    String k3 = CullSessionRegistry.key(project("com.a", "mod-a", "2.0"), false);
    assertEquals("com.a:mod-a:1.0:ut", k1);
    assertEquals("com.b:mod-b:1.0:ut", k2);
    assertEquals("com.a:mod-a:2.0:ut", k3);
  }

  private static MavenProject project(String gid, String aid, String version) {
    MavenProject p = new MavenProject();
    p.setGroupId(gid);
    p.setArtifactId(aid);
    p.setVersion(version);
    return p;
  }

  private static CullSession session(String checksum) {
    Map<String, Set<io.pjmartos.cull.core.RelPath>> emptyDeps = Collections.emptyMap();
    Map<io.pjmartos.cull.core.RelPath, byte[]> emptyHashes = Collections.emptyMap();
    return new CullSession(
        Path.of("/p"),
        Path.of("/p/target"),
        Path.of("/p/target/classes"),
        Path.of("/p/target/test-classes"),
        Path.of("/cache"),
        Path.of("/staging"),
        checksum,
        TestGraph.empty(),
        new HashMap<>(),
        Set.of(),
        Set.of(),
        false,
        false,
        false,
        5);
  }
}
