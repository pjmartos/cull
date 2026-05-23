package io.pjmartos.cull.agent;

import java.io.IOException;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Enumeration;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

public final class CullAgent {

  public static final String DEGRADED_MARKER = "agent-degraded.marker";

  private static volatile boolean degraded;
  private static volatile String degradedReason = "";

  private CullAgent() {}

  public static void premain(String args, Instrumentation inst) {
    try {
      // Step 1: Append core agent classes (ObservationWriter, RecorderHolder,
      // TestContext, IoHooks, bytecode utils, shaded core) to the bootstrap
      // classloader. These MUST be on the bootstrap classloader so that JDK
      // methods instrumented by IoHooks (FileInputStream.<init>, Files.*,
      // ClassLoader.getResource*, etc.) can call RecorderHolder.
      // We do NOT put the listeners or META-INF/services on bootstrap.
      appendSelfToBootstrap(inst);

      // Step 2: Load and initialize the agent from the bootstrap classloader.
      // We use reflection here to avoid loading ObservationWriter / RecorderHolder
      // etc. on the system classloader (which would duplicate them and cause
      // a LinkageError when listeners later reference those classes).
      ClassLoader boot = Thread.currentThread().getContextClassLoader();
      Class<?> cfgClass = Class.forName("io.pjmartos.cull.agent.AgentConfig", false, boot);
      Method parseMethod = cfgClass.getMethod("parse", String.class);
      Object cfg = parseMethod.invoke(null, args == null ? "" : args);

      Method observationFileMethod = cfgClass.getMethod("observationFile");
      Path obsFile = (Path) observationFileMethod.invoke(cfg);
      Method forkIdMethod = cfgClass.getMethod("forkId");
      String forkId = (String) forkIdMethod.invoke(cfg);
      Method ioHooksDisabledMethod = cfgClass.getMethod("ioHooksDisabled");
      boolean ioHooksDisabled = (Boolean) ioHooksDisabledMethod.invoke(cfg);

      Class<?> writerClass = Class.forName("io.pjmartos.cull.agent.ObservationWriter", false, boot);
      Constructor<?> writerCtor = writerClass.getConstructor(Path.class);
      Object writer = writerCtor.newInstance(obsFile);

      Method writeForkMetadata = writerClass.getMethod("writeForkMetadata", String.class);
      writeForkMetadata.invoke(writer, forkId);

      Class<?> ctxClass = Class.forName("io.pjmartos.cull.agent.TestContext", false, boot);
      Object ctx = ctxClass.getDeclaredConstructor().newInstance();

      Class<?> holderClass = Class.forName("io.pjmartos.cull.agent.RecorderHolder", false, boot);
      Method installMethod = holderClass.getMethod("install", ctxClass, writerClass);
      installMethod.invoke(null, ctx, writer);

      Class<?> xfClass = Class.forName("io.pjmartos.cull.agent.RecordingTransformer", false, boot);
      inst.addTransformer(
          (java.lang.instrument.ClassFileTransformer)
              xfClass.getDeclaredConstructor().newInstance(),
          false);

      if (ioHooksDisabled) {
        degraded = true;
        degradedReason = "iohooks disabled by config";
      } else {
        Class<?> ioHooksClass = Class.forName("io.pjmartos.cull.agent.IoHooks", false, boot);
        Method installIoHooks = ioHooksClass.getMethod("install", Instrumentation.class);
        boolean ok = (Boolean) installIoHooks.invoke(null, inst);
        if (!ok) {
          degraded = true;
          degradedReason = "iohooks probe failed on this JVM";
          System.err.println(
              "[cull] WARNING: I/O hooks unavailable on this JVM ("
                  + degradedReason
                  + "). Resource-change detection is degraded: any change under "
                  + "src/main/resources or src/test/resources will trigger the entire "
                  + "module's test suite. To suppress this warning, set "
                  + "-Dcull.iohooks.disabled=true to opt into degraded mode explicitly.");
        }
      }

      if (degraded) {
        writeDegradedMarker(obsFile.getParent());
      }

      // Shutdown hook uses the writer from bootstrap
      Runtime.getRuntime()
          .addShutdownHook(
              new Thread(
                  () -> {
                    try {
                      writerClass.getMethod("fsync").invoke(writer);
                      writerClass.getMethod("closeQuietly").invoke(writer);
                    } catch (Exception e) {
                      System.err.println(e.getMessage());
                    }
                  },
                  "cull-agent-shutdown"));
    } catch (Throwable t) {
      degraded = true;
      degradedReason = "agent init failed: " + t.getMessage();
      System.err.println("[cull] agent disabled: " + t);
    }
  }

  private static void writeDegradedMarker(Path stagingDir) {
    if (stagingDir == null) return;
    try {
      Files.createDirectories(stagingDir);
      Files.write(stagingDir.resolve(DEGRADED_MARKER), new byte[0]);
    } catch (IOException ignored) {
      // best effort
    }
  }

  private static Path locateSelfJar() {
    ProtectionDomain pd = CullAgent.class.getProtectionDomain();
    if (pd == null) return null;
    CodeSource cs = pd.getCodeSource();
    if (cs == null || cs.getLocation() == null) return null;
    try {
      Path p = Paths.get(cs.getLocation().toURI());
      return p.toString().endsWith(".jar") ? p : null;
    } catch (URISyntaxException e) {
      return null;
    }
  }

  private static void appendSelfToBootstrap(Instrumentation inst) {
    try {
      Path self = locateSelfJar();
      if (self == null) return;
      Path bootstrapJar = buildBootstrapJar(self);
      inst.appendToBootstrapClassLoaderSearch(new JarFile(bootstrapJar.toFile()));
    } catch (IOException | RuntimeException ignored) {
      // best effort; degraded mode handles the resulting unreachability
    }
  }

  static Path buildBootstrapJar(Path agentJar) throws IOException {
    Path tempJar = Files.createTempFile("cull-agent-bootstrap-", ".jar");
    tempJar.toFile().deleteOnExit();
    try (JarFile in = new JarFile(agentJar.toFile());
        JarOutputStream out =
            new JarOutputStream(
                Files.newOutputStream(
                    tempJar, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING))) {
      Enumeration<JarEntry> entries = in.entries();
      while (entries.hasMoreElements()) {
        JarEntry entry = entries.nextElement();
        String name = entry.getName();
        if (shouldExcludeFromBootstrap(name)) {
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
    return tempJar;
  }

  static boolean shouldExcludeFromBootstrap(String entryName) {
    if (entryName.startsWith("META-INF/services/")) return true;
    return entryName.startsWith("io/pjmartos/cull/agent/listeners/");
  }
}
