package io.pjmartos.cull.extension;

import java.nio.file.Path;
import java.util.List;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;

public final class ConfigInjector {

  static final String SUREFIRE_GAV = "org.apache.maven.plugins:maven-surefire-plugin";
  static final String FAILSAFE_GAV = "org.apache.maven.plugins:maven-failsafe-plugin";

  private ConfigInjector() {}

  public static void inject(MavenProject project, MavenSession session) {
    inject(project, session, null);
  }

  // Package-private seam: tests pass an explicit agent jar so the end-to-end
  // wiring is exercised without the embedded jar, which is only assembled at
  // package time (after the test phase). Production always passes null.
  static void inject(MavenProject project, MavenSession session, String agentJarOverride) {
    String version = resolveExtensionVersion();
    Path cacheDir = CullProperties.cacheDirectory(session);
    Path agentJar = AgentJarResolver.resolve(version, agentJarOverride, cacheDir);
    // The listener jar contains only listener classes + META-INF/services.
    // It goes on the app classpath (additionalClasspathElement) so that
    // surefire can discover the listeners via ServiceLoader, WITHOUT putting
    // core agent classes on the app classpath (which would cause a LinkageError
    // since those same classes also reside on the bootstrap classloader).
    Path listenerJar = AgentJarResolver.resolveListenerJar(version, agentJarOverride, cacheDir);
    String baseArgs = buildAgentArgs(session);
    boolean injected = false;
    if (!ForkConfig.forkDisabled(project, session, SUREFIRE_GAV)) {
      injected |=
          injectInto(project, SUREFIRE_GAV, agentJar, listenerJar, surefireArgs(baseArgs), false);
    }
    if (!ForkConfig.forkDisabled(project, session, FAILSAFE_GAV)) {
      injected |=
          injectInto(project, FAILSAFE_GAV, agentJar, listenerJar, failsafeArgs(baseArgs), true);
    }
    if (injected) {
      ensureArgLinePropertyDefault(project);
    }
  }

  // The injected Surefire/Failsafe argLine ends in `@{argLine}` so a late-bound
  // coverage agent (jacoco:prepare-agent, which sets the `argLine` property at
  // the initialize phase) is still attached. Surefire passes an unresolved
  // `@{...}` to the JVM verbatim, which crashes the fork ("could not open
  // {argLine}"). Define the property as empty up front so the placeholder
  // always resolves: to "" when no coverage agent runs, or to the agent's
  // value once prepare-agent overwrites it later in the build. A pre-existing
  // value (user- or plugin-defined) is left untouched.
  private static void ensureArgLinePropertyDefault(MavenProject project) {
    java.util.Properties props = project.getProperties();
    if (!props.containsKey("argLine")) {
      props.setProperty("argLine", "");
    }
  }

  static String surefireArgs(String baseArgs) {
    return joinArgs(baseArgs, "observations=${cull.observations.dir}");
  }

  static String failsafeArgs(String baseArgs) {
    return joinArgs(baseArgs, "observations=${cull.observations.dir.it}");
  }

  private static String joinArgs(String a, String b) {
    if (a == null || a.isEmpty()) return b;
    if (b == null || b.isEmpty()) return a;
    return a + "," + b;
  }

  private static String resolveExtensionVersion() {
    return CullVersion.get();
  }

  private static String buildAgentArgs(MavenSession session) {
    StringBuilder sb = new StringBuilder();
    if (CullProperties.ioHooksDisabled(session)) {
      sb.append("iohooksDisabled=true");
    }
    return sb.toString();
  }

  static boolean injectInto(
      MavenProject project,
      String gav,
      Path agentJar,
      Path listenerJar,
      String agentArgs,
      boolean integration) {
    Build build = project.getBuild();
    if (build == null) {
      return false;
    }
    List<Plugin> plugins = build.getPlugins();
    int index = indexOfPlugin(plugins, gav);
    if (index < 0) {
      return false;
    }
    Plugin original = plugins.get(index);
    Plugin clone = original.clone();

    String listenerClasses = detectListenerClasses(project);

    Xpp3Dom merged =
        mergeConfig(
            (Xpp3Dom) clone.getConfiguration(),
            agentJar,
            listenerJar,
            agentArgs,
            integration,
            listenerClasses);
    clone.setConfiguration(merged);

    for (PluginExecution e : clone.getExecutions()) {
      Object exCfg = e.getConfiguration();
      Xpp3Dom executionMerged =
          mergeConfig(
              exCfg instanceof Xpp3Dom ? (Xpp3Dom) exCfg : null,
              agentJar,
              listenerJar,
              agentArgs,
              integration,
              listenerClasses);
      e.setConfiguration(executionMerged);
    }

    plugins.set(index, clone);
    build.flushPluginMap();
    return true;
  }

  private static int indexOfPlugin(List<Plugin> plugins, String gav) {
    for (int i = 0; i < plugins.size(); i++) {
      if (gav.equals(plugins.get(i).getKey())) {
        return i;
      }
    }
    return -1;
  }

  // The Surefire/Failsafe `listener` property is read by the JUnit 4 and TestNG
  // providers only; JUnit 5 attribution is wired separately via the platform
  // launcher SPI (CullJunit5Listener). Listing a listener whose framework is
  // absent makes the provider fail to load it and aborts the build, so the
  // listener is injected per the framework actually on the project's test
  // classpath. When only JUnit 5 is present no property is injected.
  static String detectListenerClasses(MavenProject project) {
    if (project == null) {
      return "";
    }
    boolean junit4 = false;
    boolean testng = false;
    for (org.apache.maven.model.Dependency d : project.getDependencies()) {
      String ga = d.getGroupId() + ":" + d.getArtifactId();
      if ("junit:junit".equals(ga) || "org.junit.vintage:junit-vintage-engine".equals(ga)) {
        junit4 = true;
      } else if ("org.testng:testng".equals(ga)) {
        testng = true;
      }
    }
    StringBuilder sb = new StringBuilder();
    if (junit4) {
      sb.append("io.pjmartos.cull.agent.listeners.CullJunit4Listener");
    }
    if (testng) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append("io.pjmartos.cull.agent.listeners.CullTestngListener");
    }
    return sb.toString();
  }

  // The agent's -javaagent flag is prepended to the surefire/failsafe argLine
  // as literal text. Existing user content is preserved verbatim and our
  // segment is prepended so order-sensitive flags still work.
  //
  // When surefire has no explicit <argLine> configured, it falls back to the
  // Maven property `argLine` — the channel `jacoco-maven-plugin:prepare-agent`
  // uses to inject its own -javaagent string at the `initialize` phase. By
  // writing our own <argLine> element we shadow that fallback, so JaCoCo's
  // contribution would be lost. To preserve it, our segment is followed by
  // `@{argLine}` (surefire's late-binding placeholder, resolved at goal
  // execution against the current `argLine` property, empty if unset). If the
  // user's existing argLine already references the property (either `${argLine}`
  // or `@{argLine}`), we don't add a second reference to avoid attaching
  // JaCoCo twice.
  static Xpp3Dom mergeConfig(
      Xpp3Dom existing,
      Path agentJar,
      Path listenerJar,
      String agentArgs,
      boolean integration,
      String listenerClasses) {
    Xpp3Dom out = existing == null ? new Xpp3Dom("configuration") : new Xpp3Dom(existing);

    String agentJarPath = agentJar.toAbsolutePath().toString().replace('\\', '/');
    String listenerJarPath = listenerJar.toAbsolutePath().toString().replace('\\', '/');
    String agentSegment =
        "-javaagent:"
            + agentJarPath
            + (agentArgs == null || agentArgs.isEmpty() ? "" : "=" + agentArgs);

    Xpp3Dom argLineNode = out.getChild("argLine");
    if (argLineNode == null) {
      Xpp3Dom n = new Xpp3Dom("argLine");
      n.setValue(agentSegment + " @{argLine}");
      out.addChild(n);
    } else {
      String prev = argLineNode.getValue() == null ? "" : argLineNode.getValue();
      if (!prev.contains("cull-agent")) {
        String suffix = referencesArgLineProperty(prev) ? "" : " @{argLine}";
        argLineNode.setValue(agentSegment + suffix + (prev.isEmpty() ? "" : " " + prev));
      }
    }

    String testPlaceholder = integration ? "${cull.selected.tests.it}" : "${cull.selected.tests}";
    ensureChild(out, "test", testPlaceholder);
    ensureChild(out, "failIfNoTests", "false");
    ensureChild(out, "failIfNoSpecifiedTests", "false");

    // additionalClasspathElements - ensure the listener jar (not the full agent
    // jar) is on the app classpath. The full agent jar is only used via
    // -javaagent, which puts the core agent classes on the bootstrap classloader
    // (via appendToBootstrapClassLoaderSearch). Putting it on the app classpath
    // as well would cause a LinkageError.
    Xpp3Dom acpNode = out.getChild("additionalClasspathElements");
    if (acpNode == null) {
      acpNode = new Xpp3Dom("additionalClasspathElements");
      Xpp3Dom el = new Xpp3Dom("additionalClasspathElement");
      el.setValue(listenerJarPath);
      acpNode.addChild(el);
      out.addChild(acpNode);
    } else {
      boolean found = false;
      for (Xpp3Dom el : acpNode.getChildren("additionalClasspathElement")) {
        if (listenerJarPath.equals(el.getValue())) {
          found = true;
          break;
        }
      }
      if (!found) {
        Xpp3Dom el = new Xpp3Dom("additionalClasspathElement");
        el.setValue(listenerJarPath);
        acpNode.addChild(el);
      }
    }

    // systemPropertyVariables - publish observation dir and session id as properties
    String obsDirSuffix = integration ? ".it" : "";
    String sessionIdSuffix = integration ? ".it" : "";
    Xpp3Dom sysProps = out.getChild("systemPropertyVariables");
    if (sysProps == null) {
      sysProps = new Xpp3Dom("systemPropertyVariables");
      out.addChild(sysProps);
    }
    ensureChild(sysProps, "cull.observations.dir", "${cull.observations.dir" + obsDirSuffix + "}");
    ensureChild(sysProps, "cull.session.id", "${cull.session.id" + sessionIdSuffix + "}");

    // Register the framework listeners via the Surefire/Failsafe <properties>
    // convention (<property><name>listener</name><value>FQCN[,FQCN]</value>).
    // Only frameworks present on the project's test classpath are listed
    // (see detectListenerClasses); JUnit 5 needs no entry — it is wired via
    // the platform launcher SPI. A user-provided listener is left untouched.
    if (listenerClasses != null && !listenerClasses.isEmpty()) {
      Xpp3Dom propsNode = out.getChild("properties");
      if (propsNode == null) {
        propsNode = new Xpp3Dom("properties");
        out.addChild(propsNode);
      }
      boolean listenerFound = false;
      for (Xpp3Dom prop : propsNode.getChildren("property")) {
        Xpp3Dom nameNode = prop.getChild("name");
        if (nameNode != null && "listener".equals(nameNode.getValue())) {
          listenerFound = true;
          break;
        }
      }
      if (!listenerFound) {
        Xpp3Dom prop = new Xpp3Dom("property");
        Xpp3Dom n = new Xpp3Dom("name");
        n.setValue("listener");
        prop.addChild(n);
        Xpp3Dom v = new Xpp3Dom("value");
        v.setValue(listenerClasses);
        prop.addChild(v);
        propsNode.addChild(prop);
      }
    }

    return out;
  }

  private static void ensureChild(Xpp3Dom parent, String name, String value) {
    Xpp3Dom child = parent.getChild(name);
    if (child == null) {
      child = new Xpp3Dom(name);
      child.setValue(value);
      parent.addChild(child);
    }
  }

  // Does the user's argLine already pull in the Maven `argLine` property,
  // either early-bound ${argLine} or late-bound @{argLine}? If so, prepending
  // our own @{argLine} would attach JaCoCo (or whatever sets the property)
  // twice. Matches either form anywhere in the string, ignoring whitespace
  // between the marker and the property name.
  static boolean referencesArgLineProperty(String argLine) {
    if (argLine == null) return false;
    return argLine.contains("${argLine}") || argLine.contains("@{argLine}");
  }
}
