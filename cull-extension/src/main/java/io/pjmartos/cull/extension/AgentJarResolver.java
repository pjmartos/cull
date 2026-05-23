package io.pjmartos.cull.extension;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public final class AgentJarResolver {

  private static final String[] LISTENER_PREFIXES = {
    "META-INF/services/", "io/pjmartos/cull/agent/listeners/"
  };

  private AgentJarResolver() {}

  public static Path resolve(String version, String override, Path cacheRoot) {
    if (override != null && !override.isEmpty()) {
      return Paths.get(override);
    }
    Path sibling = findSibling(version);
    if (sibling != null && Files.isRegularFile(sibling)) {
      return sibling;
    }
    return extractEmbedded(version, cacheRoot);
  }

  /**
   * Resolve (or create) a minimal jar containing only the listener classes and META-INF/services
   * descriptors from the agent jar. This jar is used as an {@code additionalClasspathElement} for
   * surefire/failsafe so that the listeners are discoverable on the app classpath, without pulling
   * in core agent classes that also reside on the bootstrap classloader (which would cause a
   * LinkageError).
   */
  public static Path resolveListenerJar(String version, String override, Path cacheRoot) {
    Path agentJar = resolve(version, override, cacheRoot);
    if (agentJar == null) {
      throw new IllegalStateException("agent jar could not be resolved");
    }
    Path target = cacheRoot.resolve("agents").resolve(version).resolve("cull-agent-listener.jar");
    try {
      if (Files.isRegularFile(target) && !listenerJarStale(target, agentJar)) {
        return target;
      }
      Path parent = target.getParent();
      if (parent == null) {
        throw new IOException("listener jar target has no parent: " + target);
      }
      Files.createDirectories(parent);
      extractListenerClasses(agentJar, target);
      return target;
    } catch (IOException e) {
      throw new IllegalStateException("failed to create listener jar", e);
    }
  }

  private static void extractListenerClasses(Path agentJar, Path target) throws IOException {
    Path targetParent = target.getParent();
    if (targetParent == null) {
      throw new IOException("target has no parent: " + target);
    }
    Path tmp = Files.createTempFile(targetParent, "cull-listener", ".jar");
    try (JarFile in = new JarFile(agentJar.toFile());
        JarOutputStream out =
            new JarOutputStream(
                Files.newOutputStream(
                    tmp,
                    java.nio.file.StandardOpenOption.WRITE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING))) {
      java.util.Enumeration<JarEntry> entries = in.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (!isListenerEntry(name)) {
          continue;
        }
        out.putNextEntry(new JarEntry(name));
        if (!entry.isDirectory()) {
          try (InputStream is = in.getInputStream(entry)) {
            is.transferTo(out);
          }
        }
        out.closeEntry();
      }
    }
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /**
   * The listener jar is a derived subset of the agent jar. If it predates the agent jar it was
   * extracted from a stale build (e.g. a SNAPSHOT reinstall reusing the same version string) and
   * must be regenerated — otherwise newer listener/SPI code silently never takes effect. When
   * freshness cannot be proven, regenerate (favor correctness over reuse).
   */
  static boolean listenerJarStale(Path listenerJar, Path agentJar) {
    try {
      return Files.getLastModifiedTime(listenerJar).toMillis()
          < Files.getLastModifiedTime(agentJar).toMillis();
    } catch (IOException e) {
      return true;
    }
  }

  static boolean isListenerEntry(String entryName) {
    for (String prefix : LISTENER_PREFIXES) {
      if (entryName.startsWith(prefix)) {
        return true;
      }
    }
    return false;
  }

  private static Path findSibling(String version) {
    ProtectionDomain pd = AgentJarResolver.class.getProtectionDomain();
    if (pd == null) return null;
    CodeSource cs = pd.getCodeSource();
    if (cs == null) return null;
    URL location = cs.getLocation();
    if (location == null) return null;
    try {
      Path self = Paths.get(location.toURI());
      Path dir = self.getParent();
      if (dir == null) return null;
      return dir.resolve("cull-agent-" + version + ".jar");
    } catch (Exception e) {
      return null;
    }
  }

  private static Path extractEmbedded(String version, Path cacheRoot) {
    Path target = cacheRoot.resolve("agents").resolve(version).resolve("cull-agent.jar");
    try (InputStream in = AgentJarResolver.class.getResourceAsStream("/cull-agent-embedded.jar")) {
      if (in == null) {
        throw new IOException("embedded agent jar not present in extension");
      }
      byte[] bytes = in.readAllBytes();
      return materializeAgentJar(target, bytes);
    } catch (IOException e) {
      throw new IllegalStateException("failed to resolve agent jar", e);
    }
  }

  static Path materializeAgentJar(Path target, byte[] candidate) throws IOException {
    if (Files.isRegularFile(target) && contentMatches(target, candidate)) {
      return target;
    }
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("agent jar target has no parent directory: " + target);
    }
    Files.createDirectories(parent);
    Path tmp = Files.createTempFile(parent, "cull-agent", ".jar");
    Files.write(tmp, candidate);
    try {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (java.nio.file.AtomicMoveNotSupportedException e) {
      Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
    }
    return target;
  }

  static boolean contentMatches(Path target, byte[] candidate) {
    try {
      byte[] existing = Files.readAllBytes(target);
      if (existing.length != candidate.length) return false;
      for (int i = 0; i < existing.length; i++) {
        if (existing[i] != candidate[i]) return false;
      }
      return true;
    } catch (IOException e) {
      return false;
    }
  }
}
