package io.pjmartos.cull.agent;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link CullAgent}: the bootstrap jar filter and {@link CullAgent#premain} driven with a
 * fake {@link Instrumentation}, including the degraded-mode and never-throws boundaries.
 */
class CullAgentTest {

  @Test
  void excludesListenersAndServices() {
    assertTrue(CullAgent.shouldExcludeFromBootstrap("META-INF/services/x"));
    assertTrue(
        CullAgent.shouldExcludeFromBootstrap(
            "META-INF/services/org.junit.platform.launcher.TestExecutionListener"));
    assertTrue(
        CullAgent.shouldExcludeFromBootstrap(
            "io/pjmartos/cull/agent/listeners/CullJunit5Listener.class"));
    assertTrue(
        CullAgent.shouldExcludeFromBootstrap(
            "io/pjmartos/cull/agent/listeners/CullJunit4Listener.class"));
    assertTrue(
        CullAgent.shouldExcludeFromBootstrap(
            "io/pjmartos/cull/agent/listeners/TestClassNames.class"));
    assertFalse(
        CullAgent.shouldExcludeFromBootstrap("io/pjmartos/cull/agent/RecorderHolder.class"));
    assertFalse(CullAgent.shouldExcludeFromBootstrap("io/pjmartos/cull/agent/TestContext.class"));
    assertFalse(CullAgent.shouldExcludeFromBootstrap("META-INF/MANIFEST.MF"));
  }

  @Test
  void buildsFilteredJar(@TempDir Path tmp) throws IOException {
    Path src = tmp.resolve("source.jar");
    try (OutputStream os = Files.newOutputStream(src);
        JarOutputStream jos = new JarOutputStream(os)) {
      addEntry(jos, "io/pjmartos/cull/agent/RecorderHolder.class", new byte[] {1, 2, 3});
      addEntry(jos, "io/pjmartos/cull/agent/TestContext.class", new byte[] {4, 5, 6});
      addEntry(jos, "io/pjmartos/cull/agent/listeners/CullJunit5Listener.class", new byte[] {7});
      addEntry(
          jos,
          "META-INF/services/org.junit.platform.launcher.TestExecutionListener",
          "io.pjmartos.cull.agent.listeners.CullJunit5Listener\n".getBytes());
      addEntry(jos, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes());
    }
    Path filtered = CullAgent.buildBootstrapJar(src);

    Set<String> entries = new HashSet<>();
    try (JarFile jf = new JarFile(filtered.toFile())) {
      jf.stream().map(JarEntry::getName).forEach(entries::add);
    }
    assertTrue(entries.contains("io/pjmartos/cull/agent/RecorderHolder.class"));
    assertTrue(entries.contains("io/pjmartos/cull/agent/TestContext.class"));
    assertTrue(entries.contains("META-INF/MANIFEST.MF"));
    assertFalse(entries.contains("io/pjmartos/cull/agent/listeners/CullJunit5Listener.class"));
    assertFalse(
        entries.contains("META-INF/services/org.junit.platform.launcher.TestExecutionListener"));
  }

  @Test
  void degradedMarkerConstantIsStable() {
    assertEquals("agent-degraded.marker", CullAgent.DEGRADED_MARKER);
  }

  @Test
  void shouldExcludeFromBootstrapBoundaries() {
    assertTrue(CullAgent.shouldExcludeFromBootstrap("META-INF/services/"));
    assertFalse(
        CullAgent.shouldExcludeFromBootstrap("META-INF/services"),
        "the prefix requires the trailing slash");
    assertFalse(
        CullAgent.shouldExcludeFromBootstrap("io/pjmartos/cull/agent/listeners"),
        "the prefix requires the trailing slash");
    assertFalse(
        CullAgent.shouldExcludeFromBootstrap("io/pjmartos/cull/agent/listenersHelper/X.class"),
        "a sibling package that merely shares a name prefix must not be excluded");
    assertFalse(CullAgent.shouldExcludeFromBootstrap(""));
  }

  @Test
  void buildBootstrapJarKeepsDirectoryEntriesAndDropsListenerTree(@TempDir Path tmp)
      throws IOException {
    Path src = tmp.resolve("source.jar");
    try (OutputStream os = Files.newOutputStream(src);
        JarOutputStream jos = new JarOutputStream(os)) {
      addEntry(jos, "io/pjmartos/cull/agent/", new byte[0]);
      addEntry(jos, "io/pjmartos/cull/agent/RecorderHolder.class", new byte[] {1, 2, 3});
      addEntry(jos, "io/pjmartos/cull/agent/listeners/", new byte[0]);
      addEntry(jos, "io/pjmartos/cull/agent/listeners/CullJunit5Listener.class", new byte[] {7});
      addEntry(
          jos,
          "META-INF/services/org.junit.platform.launcher.TestExecutionListener",
          new byte[] {9});
      addEntry(jos, "META-INF/MANIFEST.MF", "Manifest-Version: 1.0\n".getBytes());
    }

    Path filtered = CullAgent.buildBootstrapJar(src);

    Set<String> entries = new HashSet<>();
    try (JarFile jf = new JarFile(filtered.toFile())) {
      jf.stream().map(JarEntry::getName).forEach(entries::add);
    }
    assertTrue(entries.contains("io/pjmartos/cull/agent/"), "kept directory entry preserved");
    assertTrue(entries.contains("io/pjmartos/cull/agent/RecorderHolder.class"));
    assertTrue(entries.contains("META-INF/MANIFEST.MF"));
    assertFalse(entries.contains("io/pjmartos/cull/agent/listeners/"));
    assertFalse(entries.contains("io/pjmartos/cull/agent/listeners/CullJunit5Listener.class"));
    assertFalse(
        entries.contains("META-INF/services/org.junit.platform.launcher.TestExecutionListener"));
  }

  @Test
  void premainEntersDegradedModeAndWritesMarkerWhenIoHooksDisabled(@TempDir Path tmp) {
    FakeInstrumentation fake = new FakeInstrumentation(false);
    String args = "observations=" + tmp + ",iohooksDisabled=true";

    assertDoesNotThrow(() -> CullAgent.premain(args, fake));

    assertFalse(fake.transformers.isEmpty(), "a recording transformer must be registered");
    assertTrue(
        Files.isRegularFile(tmp.resolve(CullAgent.DEGRADED_MARKER)),
        "iohooks-disabled means degraded mode, which drops the marker file");
    assertTrue(observationFileExists(tmp), "the observation file must have been opened");
  }

  @Test
  void premainNeverThrowsWhenInstrumentationFails(@TempDir Path tmp) {
    FakeInstrumentation fake = new FakeInstrumentation(true);
    String args = "observations=" + tmp + ",iohooksDisabled=true";
    assertDoesNotThrow(
        () -> CullAgent.premain(args, fake),
        "cull never fails the build: premain must swallow every failure");
  }

  @Test
  void premainHandlesNullArgsViaSystemProperties(@TempDir Path tmp) {
    String prevObs = System.getProperty("cull.observations.dir");
    String prevHooks = System.getProperty("cull.iohooks.disabled");
    System.setProperty("cull.observations.dir", tmp.toString());
    System.setProperty("cull.iohooks.disabled", "true");
    try {
      FakeInstrumentation fake = new FakeInstrumentation(false);
      assertDoesNotThrow(() -> CullAgent.premain(null, fake));
      assertTrue(Files.isRegularFile(tmp.resolve(CullAgent.DEGRADED_MARKER)));
    } finally {
      restore("cull.observations.dir", prevObs);
      restore("cull.iohooks.disabled", prevHooks);
    }
  }

  private static void restore(String key, String prev) {
    if (prev == null) {
      System.clearProperty(key);
    } else {
      System.setProperty(key, prev);
    }
  }

  private static boolean observationFileExists(Path dir) {
    try (var stream = Files.list(dir)) {
      return stream.anyMatch(p -> p.getFileName().toString().startsWith("observations-"));
    } catch (IOException e) {
      return false;
    }
  }

  private static void addEntry(JarOutputStream jos, String name, byte[] body) throws IOException {
    jos.putNextEntry(new JarEntry(name));
    if (body.length > 0) {
      jos.write(body);
    }
    jos.closeEntry();
  }

  private static final class FakeInstrumentation implements Instrumentation {
    private final boolean failOnAddTransformer;
    private final List<ClassFileTransformer> transformers = new ArrayList<>();

    FakeInstrumentation(boolean failOnAddTransformer) {
      this.failOnAddTransformer = failOnAddTransformer;
    }

    @Override
    public void addTransformer(ClassFileTransformer transformer, boolean canRetransform) {
      if (failOnAddTransformer) {
        throw new IllegalStateException("simulated instrumentation failure");
      }
      transformers.add(transformer);
    }

    @Override
    public void addTransformer(ClassFileTransformer transformer) {
      addTransformer(transformer, false);
    }

    @Override
    public boolean removeTransformer(ClassFileTransformer transformer) {
      return transformers.remove(transformer);
    }

    @Override
    public boolean isRetransformClassesSupported() {
      return true;
    }

    @Override
    public void retransformClasses(Class<?>... classes) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isRedefineClassesSupported() {
      return false;
    }

    @Override
    public void redefineClasses(ClassDefinition... definitions) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isModifiableClass(Class<?> theClass) {
      return true;
    }

    @Override
    public Class<?>[] getAllLoadedClasses() {
      return new Class<?>[0];
    }

    @Override
    public Class<?>[] getInitiatedClasses(ClassLoader loader) {
      return new Class<?>[0];
    }

    @Override
    public long getObjectSize(Object objectToSize) {
      return 0L;
    }

    @Override
    public void appendToBootstrapClassLoaderSearch(JarFile jarfile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void appendToSystemClassLoaderSearch(JarFile jarfile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isNativeMethodPrefixSupported() {
      return false;
    }

    @Override
    public void setNativeMethodPrefix(ClassFileTransformer transformer, String prefix) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isModifiableModule(Module module) {
      return false;
    }

    @Override
    public void redefineModule(
        Module module,
        Set<Module> extraReads,
        java.util.Map<String, Set<Module>> extraExports,
        java.util.Map<String, Set<Module>> extraOpens,
        Set<Class<?>> extraUses,
        java.util.Map<Class<?>, List<Class<?>>> extraProvides) {
      throw new UnsupportedOperationException();
    }
  }
}
