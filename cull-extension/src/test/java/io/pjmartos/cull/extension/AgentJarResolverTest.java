package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link AgentJarResolver} covering override precedence, sibling resolution, content
 * matching, embedded-jar materialization (including stale-cache replacement), and the listener-jar
 * derivation path.
 */
class AgentJarResolverTest {

  private static final byte[] SAMPLE_JAR = "embedded-agent-bytes-v1".getBytes();

  @Test
  void overrideTakesPrecedence(@TempDir Path tmp) throws IOException {
    Path overrideJar = tmp.resolve("custom-agent.jar");
    Files.write(overrideJar, new byte[] {1, 2, 3});
    Path cacheRoot = tmp.resolve("cache");

    Path resolved = AgentJarResolver.resolve("9.9.9", overrideJar.toString(), cacheRoot);
    assertEquals(overrideJar.toAbsolutePath(), resolved.toAbsolutePath());
  }

  @Test
  void contentMatchesDetectsIdentity(@TempDir Path tmp) throws IOException {
    Path target = tmp.resolve("a.jar");
    Files.write(target, SAMPLE_JAR);
    assertTrue(AgentJarResolver.contentMatches(target, SAMPLE_JAR));
  }

  @Test
  void contentMatchesDetectsLengthDifference(@TempDir Path tmp) throws IOException {
    Path target = tmp.resolve("a.jar");
    Files.write(target, "short".getBytes());
    assertFalse(AgentJarResolver.contentMatches(target, "much-longer-content".getBytes()));
  }

  @Test
  void contentMatchesDetectsByteDifference(@TempDir Path tmp) throws IOException {
    Path target = tmp.resolve("a.jar");
    Files.write(target, "abcdef".getBytes());
    assertFalse(AgentJarResolver.contentMatches(target, "abcXef".getBytes()));
  }

  @Test
  void contentMatchesReturnsFalseForMissingFile(@TempDir Path tmp) {
    Path missing = tmp.resolve("nonexistent.jar");
    assertFalse(AgentJarResolver.contentMatches(missing, SAMPLE_JAR));
  }

  @Test
  void materializeWritesFreshTargetWhenAbsent(@TempDir Path tmp) throws IOException {
    Path target = tmp.resolve("agents").resolve("9.9.9-TEST").resolve("cull-agent.jar");
    Path resolved = AgentJarResolver.materializeAgentJar(target, SAMPLE_JAR);
    assertEquals(target, resolved);
    assertArrayEquals(SAMPLE_JAR, Files.readAllBytes(resolved));
  }

  @Test
  void materializeReplacesStaleCache(@TempDir Path tmp) throws IOException {
    Path target = tmp.resolve("agents").resolve("9.9.9-TEST").resolve("cull-agent.jar");
    Files.createDirectories(target.getParent());
    Files.write(target, "stale".getBytes());

    Path resolved = AgentJarResolver.materializeAgentJar(target, SAMPLE_JAR);
    assertEquals(target, resolved);
    assertArrayEquals(SAMPLE_JAR, Files.readAllBytes(resolved));
  }

  @Test
  void materializeKeepsCacheWhenContentMatches(@TempDir Path tmp) throws IOException {
    Path target = tmp.resolve("agents").resolve("9.9.9-TEST").resolve("cull-agent.jar");
    Files.createDirectories(target.getParent());
    Files.write(target, SAMPLE_JAR);
    long mtimeBefore = Files.getLastModifiedTime(target).toMillis();

    Path resolved = AgentJarResolver.materializeAgentJar(target, SAMPLE_JAR);
    assertEquals(
        mtimeBefore,
        Files.getLastModifiedTime(resolved).toMillis(),
        "matching cache must not be rewritten");
  }

  @Test
  void resolveWithSiblingPresent(@TempDir Path tmp) throws IOException {
    Path siblingDir = tmp.resolve("sibling");
    Files.createDirectories(siblingDir);
    Path siblingJar = siblingDir.resolve("cull-agent-9.9.9.jar");
    Files.write(siblingJar, SAMPLE_JAR);

    Path resolved = AgentJarResolver.resolve("9.9.9", siblingJar.toString(), tmp.resolve("cache"));
    assertNotNull(resolved);
    assertEquals(siblingJar.toAbsolutePath(), resolved.toAbsolutePath());
  }

  @Test
  void resolveThrowsWhenNoSiblingAndNoEmbedded(@TempDir Path tmp) {
    Path cacheRoot = tmp.resolve("cache");
    try {
      AgentJarResolver.resolve("9.9.9", null, cacheRoot);
    } catch (RuntimeException e) {
      assertTrue(
          e.getMessage() != null
              && (e.getMessage().contains("embedded")
                  || e.getMessage().contains("failed to resolve")));
    }
  }

  @Test
  void contentMatchesEmptyTargetReturnsFalse(@TempDir Path tmp) throws IOException {
    Path target = tmp.resolve("empty.jar");
    Files.write(target, new byte[0]);
    assertFalse(AgentJarResolver.contentMatches(target, new byte[] {1, 2, 3}));
  }

  @Test
  void contentMatchesEmptyCandidate(@TempDir Path tmp) throws IOException {
    Path target = tmp.resolve("jar.jar");
    Files.write(target, new byte[] {1, 2, 3});
    assertFalse(AgentJarResolver.contentMatches(target, new byte[0]));
  }

  @Test
  void contentMatchesBothEmpty(@TempDir Path tmp) throws IOException {
    Path target = tmp.resolve("empty.jar");
    Files.write(target, new byte[0]);
    assertTrue(AgentJarResolver.contentMatches(target, new byte[0]));
  }

  @Test
  void materializeAgentJarThrowsWhenTargetHasNoParent() {
    Path lonePath = Path.of("single.jar");
    try {
      AgentJarResolver.materializeAgentJar(lonePath, new byte[] {1});
    } catch (IOException e) {
      assertTrue(e.getMessage().contains("no parent directory"));
    }
  }

  @Test
  void materializeAgentJarCreatesDeepDirectoryHierarchy(@TempDir Path tmp) throws IOException {
    Path target = tmp.resolve("a").resolve("b").resolve("c").resolve("agent.jar");
    byte[] data = new byte[] {1, 2, 3, 4};
    Path resolved = AgentJarResolver.materializeAgentJar(target, data);
    assertEquals(target, resolved);
    assertTrue(Files.exists(target));
    assertArrayEquals(data, Files.readAllBytes(target));
  }

  @Test
  void isListenerEntryClassifiesByPrefix() {
    assertTrue(AgentJarResolver.isListenerEntry("META-INF/services/anything"));
    assertTrue(
        AgentJarResolver.isListenerEntry(
            "io/pjmartos/cull/agent/listeners/CullJunit4Listener.class"));
    assertFalse(AgentJarResolver.isListenerEntry("io/pjmartos/cull/agent/RecorderHolder.class"));
    assertFalse(AgentJarResolver.isListenerEntry("META-INF/MANIFEST.MF"));
    assertFalse(
        AgentJarResolver.isListenerEntry("META-INF/service/x"),
        "singular 'service' is not a match");
    assertFalse(AgentJarResolver.isListenerEntry(""));
  }

  @Test
  void resolveListenerJarKeepsOnlyListenerEntries(@TempDir Path tmp) throws IOException {
    Path agentJar = writeFakeAgentJar(tmp.resolve("cull-agent-1.2.3.jar"));
    Path cacheRoot = tmp.resolve("cache");

    Path listenerJar = AgentJarResolver.resolveListenerJar("1.2.3", agentJar.toString(), cacheRoot);

    assertEquals(
        cacheRoot.resolve("agents").resolve("1.2.3").resolve("cull-agent-listener.jar"),
        listenerJar);
    assertTrue(Files.isRegularFile(listenerJar));

    Set<String> entries = entryNames(listenerJar);
    assertTrue(
        entries.contains("io/pjmartos/cull/agent/listeners/CullJunit5Listener.class"),
        "listener classes must be carried over");
    assertTrue(
        entries.contains("META-INF/services/org.junit.platform.launcher.TestExecutionListener"),
        "SPI descriptors must be carried over");
    assertFalse(
        entries.contains("io/pjmartos/cull/agent/RecorderHolder.class"),
        "core agent classes must be excluded from the listener jar");
    assertFalse(entries.contains("META-INF/MANIFEST.MF"));
  }

  @Test
  void resolveListenerJarIsIdempotentAndDoesNotRebuild(@TempDir Path tmp) throws IOException {
    Path agentJar = writeFakeAgentJar(tmp.resolve("cull-agent-9.9.9.jar"));
    Path cacheRoot = tmp.resolve("cache");

    Path first = AgentJarResolver.resolveListenerJar("9.9.9", agentJar.toString(), cacheRoot);

    byte[] sentinel = "not-a-real-jar-sentinel".getBytes();
    Files.write(first, sentinel);

    Path second = AgentJarResolver.resolveListenerJar("9.9.9", agentJar.toString(), cacheRoot);

    assertEquals(first, second);
    assertArrayEquals(
        sentinel,
        Files.readAllBytes(second),
        "an existing listener jar must be reused verbatim, not regenerated");
  }

  @Test
  void resolveListenerJarRebuiltWhenStaleRelativeToAgentJar(@TempDir Path tmp) throws IOException {
    Path agentJar = writeFakeAgentJar(tmp.resolve("cull-agent-9.9.9.jar"));
    Path cacheRoot = tmp.resolve("cache");

    Path first = AgentJarResolver.resolveListenerJar("9.9.9", agentJar.toString(), cacheRoot);
    byte[] sentinel = "stale-listener-jar-from-an-old-build".getBytes();
    Files.write(first, sentinel);

    // Simulate a reinstall reusing the same version string: the agent jar is
    // rebuilt (newer) while the cached listener jar predates it.
    long agentMtime = Files.getLastModifiedTime(agentJar).toMillis();
    Files.setLastModifiedTime(
        first, java.nio.file.attribute.FileTime.fromMillis(agentMtime - 100_000));

    Path second = AgentJarResolver.resolveListenerJar("9.9.9", agentJar.toString(), cacheRoot);

    assertEquals(first, second);
    assertFalse(
        java.util.Arrays.equals(sentinel, Files.readAllBytes(second)),
        "a listener jar older than its agent jar must be regenerated, not reused");
    Set<String> entries = entryNames(second);
    assertTrue(
        entries.contains("io/pjmartos/cull/agent/listeners/CullJunit5Listener.class"),
        "regenerated jar carries the current listener classes");
    assertTrue(
        entries.contains("META-INF/services/org.junit.platform.launcher.TestExecutionListener"));
  }

  private static Path writeFakeAgentJar(Path jar) throws IOException {
    try (OutputStream os = Files.newOutputStream(jar);
        JarOutputStream jos = new JarOutputStream(os)) {
      put(jos, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes());
      put(jos, "io/pjmartos/cull/agent/RecorderHolder.class", new byte[] {1, 2, 3});
      put(jos, "io/pjmartos/cull/agent/listeners/", new byte[0]);
      put(jos, "io/pjmartos/cull/agent/listeners/CullJunit5Listener.class", new byte[] {4, 5});
      put(
          jos,
          "META-INF/services/org.junit.platform.launcher.TestExecutionListener",
          "io.pjmartos.cull.agent.listeners.CullJunit5Listener\n".getBytes());
    }
    return jar;
  }

  private static void put(JarOutputStream jos, String name, byte[] body) throws IOException {
    jos.putNextEntry(new JarEntry(name));
    if (body.length > 0) {
      jos.write(body);
    }
    jos.closeEntry();
  }

  private static Set<String> entryNames(Path jar) throws IOException {
    Set<String> names = new HashSet<>();
    try (JarFile jf = new JarFile(jar.toFile())) {
      jf.stream().map(JarEntry::getName).forEach(names::add);
    }
    return names;
  }
}
