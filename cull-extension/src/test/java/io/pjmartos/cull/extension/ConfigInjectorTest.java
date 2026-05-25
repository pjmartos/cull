package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.jar.JarOutputStream;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Dependency;
import org.apache.maven.model.Plugin;
import org.apache.maven.model.PluginExecution;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link ConfigInjector} — the surefire/failsafe configuration weaver. Covers agent-arg
 * joining, framework-listener detection, the {@code mergeConfig} DOM surgery (idempotency,
 * user-content preservation, integration suffixes, non-destructive merging), {@code injectInto}
 * cloning semantics, and the end-to-end {@code inject} including the {@link ForkConfig} gate that
 * suppresses injection when forking is disabled.
 */
class ConfigInjectorTest {

  private static final String SUREFIRE = ConfigInjector.SUREFIRE_GAV;
  private static final String FAILSAFE = ConfigInjector.FAILSAFE_GAV;
  private static final String JUNIT4 = "io.pjmartos.cull.agent.listeners.CullJunit4Listener";
  private static final String TESTNG = "io.pjmartos.cull.agent.listeners.CullTestngListener";

  // -- agent-arg joining --

  @Nested
  class AgentArgs {

    @Test
    void surefireArgsWithEmptyBaseIsJustObservations() {
      assertEquals("observations=${cull.observations.dir}", ConfigInjector.surefireArgs(""));
    }

    @Test
    void surefireArgsWithBaseIsCommaJoined() {
      assertEquals(
          "iohooksDisabled=true,observations=${cull.observations.dir}",
          ConfigInjector.surefireArgs("iohooksDisabled=true"));
    }

    @Test
    void failsafeArgsUsesItObservationKeys() {
      assertEquals("observations=${cull.observations.dir.it}", ConfigInjector.failsafeArgs(""));
      assertEquals(
          "iohooksDisabled=true,observations=${cull.observations.dir.it}",
          ConfigInjector.failsafeArgs("iohooksDisabled=true"));
    }

    @Test
    void nullBaseIsTreatedAsEmpty() {
      assertEquals("observations=${cull.observations.dir}", ConfigInjector.surefireArgs(null));
    }
  }

  // -- framework listener detection --

  @Nested
  class DetectListenerClasses {

    @Test
    void nullProjectIsEmpty() {
      assertEquals("", ConfigInjector.detectListenerClasses(null));
    }

    @Test
    void noTestFrameworkDependenciesIsEmpty() {
      assertEquals("", ConfigInjector.detectListenerClasses(projectWithDeps()));
    }

    @Test
    void junit5OnlyGetsNoListenerProperty() {
      // JUnit 5 attribution is wired via the platform launcher SPI, not the
      // surefire `listener` property, so no class is registered here.
      assertEquals(
          "",
          ConfigInjector.detectListenerClasses(
              projectWithDeps(dep("org.junit.jupiter", "junit-jupiter"))));
    }

    @Test
    void junit4MapsToJunit4Listener() {
      assertEquals(
          JUNIT4, ConfigInjector.detectListenerClasses(projectWithDeps(dep("junit", "junit"))));
    }

    @Test
    void vintageEngineCountsAsJunit4() {
      assertEquals(
          JUNIT4,
          ConfigInjector.detectListenerClasses(
              projectWithDeps(dep("org.junit.vintage", "junit-vintage-engine"))));
    }

    @Test
    void testngMapsToTestngListener() {
      assertEquals(
          TESTNG,
          ConfigInjector.detectListenerClasses(projectWithDeps(dep("org.testng", "testng"))));
    }

    @Test
    void junit4AndTestngAreCommaJoinedInOrder() {
      assertEquals(
          JUNIT4 + "," + TESTNG,
          ConfigInjector.detectListenerClasses(
              projectWithDeps(dep("org.testng", "testng"), dep("junit", "junit"))));
    }
  }

  // -- mergeConfig DOM surgery --

  @Nested
  class MergeConfig {

    private final Path agentJar = Path.of("build", "cull-agent.jar");
    private final Path listenerJar = Path.of("build", "cull-agent-listener.jar");

    @Test
    void fromEmptyConfigBuildsTheFullSurefireShape() {
      Xpp3Dom out =
          ConfigInjector.mergeConfig(null, agentJar, listenerJar, "observations=x", false, "");

      assertEquals(
          "-javaagent:" + norm(agentJar) + "=observations=x @{argLine}", child(out, "argLine"));
      assertEquals("${cull.selected.tests}", child(out, "test"));
      assertEquals("false", child(out, "failIfNoTests"));
      assertEquals("false", child(out, "failIfNoSpecifiedTests"));
      assertEquals(
          norm(listenerJar),
          out.getChild("additionalClasspathElements")
              .getChild("additionalClasspathElement")
              .getValue());
      Xpp3Dom sys = out.getChild("systemPropertyVariables");
      assertEquals("${cull.observations.dir}", sys.getChild("cull.observations.dir").getValue());
      assertEquals("${cull.session.id}", sys.getChild("cull.session.id").getValue());
      assertNull(out.getChild("properties"), "no listener classes -> no <properties> node");
    }

    @Test
    void integrationUsesItPlaceholders() {
      Xpp3Dom out =
          ConfigInjector.mergeConfig(null, agentJar, listenerJar, "observations=x", true, "");
      assertEquals("${cull.selected.tests.it}", child(out, "test"));
      Xpp3Dom sys = out.getChild("systemPropertyVariables");
      assertEquals("${cull.observations.dir.it}", sys.getChild("cull.observations.dir").getValue());
      assertEquals("${cull.session.id.it}", sys.getChild("cull.session.id").getValue());
    }

    @Test
    void emptyAgentArgsOmitsTheEqualsSuffix() {
      Xpp3Dom out = ConfigInjector.mergeConfig(null, agentJar, listenerJar, "", false, "");
      assertEquals("-javaagent:" + norm(agentJar) + " @{argLine}", child(out, "argLine"));
    }

    @Test
    void normalizesBackslashesInAgentPath() {
      Xpp3Dom out = ConfigInjector.mergeConfig(null, agentJar, listenerJar, "a", false, "");
      assertFalse(child(out, "argLine").contains("\\"), "windows separators must be normalized");
    }

    @Test
    void existingUserArgLineIsPreservedAndAgentPrepended() {
      Xpp3Dom existing = config();
      addChild(existing, "argLine", "-Xmx512m");
      Xpp3Dom out = ConfigInjector.mergeConfig(existing, agentJar, listenerJar, "a", false, "");
      assertEquals(
          "-javaagent:" + norm(agentJar) + "=a @{argLine} -Xmx512m", child(out, "argLine"));
    }

    @Test
    void existingArgLineWithLateBoundArgLineRefDoesNotDoubleInject() {
      // The user (or another plugin) already pulls in the Maven `argLine`
      // property via @{argLine}, the surefire-recommended JaCoCo idiom.
      // Adding our own @{argLine} would attach JaCoCo's agent twice.
      Xpp3Dom existing = config();
      addChild(existing, "argLine", "@{argLine} -Xmx2G");
      Xpp3Dom out = ConfigInjector.mergeConfig(existing, agentJar, listenerJar, "a", false, "");
      assertEquals("-javaagent:" + norm(agentJar) + "=a @{argLine} -Xmx2G", child(out, "argLine"));
    }

    @Test
    void existingArgLineWithEarlyBoundArgLineRefDoesNotDoubleInject() {
      // The legacy JaCoCo idiom uses ${argLine}. Same reasoning as the
      // late-bound case — don't double-inject.
      Xpp3Dom existing = config();
      addChild(existing, "argLine", "${argLine} -Xmx2G");
      Xpp3Dom out = ConfigInjector.mergeConfig(existing, agentJar, listenerJar, "a", false, "");
      assertEquals("-javaagent:" + norm(agentJar) + "=a ${argLine} -Xmx2G", child(out, "argLine"));
    }

    @Test
    void argLineAlreadyCarryingAgentIsLeftUntouched() {
      Xpp3Dom existing = config();
      addChild(existing, "argLine", "-javaagent:/opt/cull-agent.jar=old KEEP");
      Xpp3Dom out = ConfigInjector.mergeConfig(existing, agentJar, listenerJar, "a", false, "");
      assertEquals("-javaagent:/opt/cull-agent.jar=old KEEP", child(out, "argLine"));
    }

    @Test
    void doesNotMutateTheCallerSuppliedDom() {
      Xpp3Dom existing = config();
      addChild(existing, "forkCount", "1C");
      ConfigInjector.mergeConfig(existing, agentJar, listenerJar, "a", false, "");
      assertNull(existing.getChild("argLine"), "mergeConfig must operate on a copy");
      assertEquals(1, existing.getChildCount());
    }

    @Test
    void listenerJarNotDuplicatedWhenAlreadyPresent() {
      Xpp3Dom existing = config();
      Xpp3Dom acp = new Xpp3Dom("additionalClasspathElements");
      Xpp3Dom el = new Xpp3Dom("additionalClasspathElement");
      el.setValue(norm(listenerJar));
      acp.addChild(el);
      existing.addChild(acp);

      Xpp3Dom out = ConfigInjector.mergeConfig(existing, agentJar, listenerJar, "a", false, "");
      assertEquals(
          1, out.getChild("additionalClasspathElements").getChildCount(), "no duplicate entry");
    }

    @Test
    void listenerJarAppendedAlongsideExistingUserClasspathElement() {
      Xpp3Dom existing = config();
      Xpp3Dom acp = new Xpp3Dom("additionalClasspathElements");
      Xpp3Dom el = new Xpp3Dom("additionalClasspathElement");
      el.setValue("/user/extra.jar");
      acp.addChild(el);
      existing.addChild(acp);

      Xpp3Dom out = ConfigInjector.mergeConfig(existing, agentJar, listenerJar, "a", false, "");
      Xpp3Dom merged = out.getChild("additionalClasspathElements");
      assertEquals(2, merged.getChildCount());
      assertTrue(
          containsElementValue(merged, "/user/extra.jar")
              && containsElementValue(merged, norm(listenerJar)));
    }

    @Test
    void userSystemPropertyVariableIsNotOverwritten() {
      Xpp3Dom existing = config();
      Xpp3Dom sys = new Xpp3Dom("systemPropertyVariables");
      Xpp3Dom user = new Xpp3Dom("cull.observations.dir");
      user.setValue("USER_OVERRIDE");
      sys.addChild(user);
      existing.addChild(sys);

      Xpp3Dom out = ConfigInjector.mergeConfig(existing, agentJar, listenerJar, "a", false, "");
      Xpp3Dom outSys = out.getChild("systemPropertyVariables");
      assertEquals("USER_OVERRIDE", outSys.getChild("cull.observations.dir").getValue());
      assertEquals("${cull.session.id}", outSys.getChild("cull.session.id").getValue());
    }

    @Test
    void listenerPropertyAddedWhenClassesDetected() {
      Xpp3Dom out = ConfigInjector.mergeConfig(null, agentJar, listenerJar, "a", false, JUNIT4);
      Xpp3Dom prop = out.getChild("properties").getChild("property");
      assertEquals("listener", prop.getChild("name").getValue());
      assertEquals(JUNIT4, prop.getChild("value").getValue());
    }

    @Test
    void userProvidedListenerPropertyIsLeftUntouched() {
      Xpp3Dom existing = config();
      Xpp3Dom props = new Xpp3Dom("properties");
      Xpp3Dom prop = new Xpp3Dom("property");
      Xpp3Dom name = new Xpp3Dom("name");
      name.setValue("listener");
      Xpp3Dom value = new Xpp3Dom("value");
      value.setValue("com.acme.UserListener");
      prop.addChild(name);
      prop.addChild(value);
      props.addChild(prop);
      existing.addChild(props);

      Xpp3Dom out = ConfigInjector.mergeConfig(existing, agentJar, listenerJar, "a", false, JUNIT4);
      Xpp3Dom outProps = out.getChild("properties");
      assertEquals(1, outProps.getChildCount(), "user listener kept, none appended");
      assertEquals(
          "com.acme.UserListener", outProps.getChild("property").getChild("value").getValue());
    }
  }

  // -- injectInto cloning + execution propagation --

  @Nested
  class InjectInto {

    private final Path agentJar = Path.of("build", "cull-agent.jar");
    private final Path listenerJar = Path.of("build", "cull-agent-listener.jar");

    @Test
    void absentPluginIsANoOp() {
      MavenProject p = projectWithPlugins();
      ConfigInjector.injectInto(p, SUREFIRE, agentJar, listenerJar, "a", false);
      assertTrue(p.getBuild().getPlugins().isEmpty());
    }

    @Test
    void pluginLevelConfigGetsAgentWired() {
      Plugin sf = plugin(SUREFIRE, null);
      MavenProject p = projectWithPlugins(sf);

      ConfigInjector.injectInto(p, SUREFIRE, agentJar, listenerJar, "obs=x", false);

      Plugin injected = p.getBuild().getPluginsAsMap().get(SUREFIRE);
      Xpp3Dom cfg = (Xpp3Dom) injected.getConfiguration();
      assertNotNull(cfg);
      assertTrue(cfg.getChild("argLine").getValue().startsWith("-javaagent:"));
      assertNull(sf.getConfiguration(), "the original plugin must not be mutated (clone is used)");
    }

    @Test
    void executionLevelConfigIsAlsoWired() {
      Plugin sf = plugin(SUREFIRE, null);
      PluginExecution exec = new PluginExecution();
      exec.setId("default-test");
      Xpp3Dom execCfg = config();
      addChild(execCfg, "skipTests", "false");
      exec.setConfiguration(execCfg);
      sf.addExecution(exec);
      MavenProject p = projectWithPlugins(sf);

      ConfigInjector.injectInto(p, SUREFIRE, agentJar, listenerJar, "obs=x", false);

      Plugin injected = p.getBuild().getPluginsAsMap().get(SUREFIRE);
      Xpp3Dom mergedExec = (Xpp3Dom) injected.getExecutions().get(0).getConfiguration();
      assertNotNull(mergedExec);
      assertTrue(mergedExec.getChild("argLine").getValue().startsWith("-javaagent:"));
      assertEquals("false", mergedExec.getChild("skipTests").getValue(), "user config preserved");
    }
  }

  // -- end-to-end inject, including the ForkConfig gate --

  @Nested
  class Inject {

    @Test
    void wiresSurefireWhenForkingIsEnabled(@TempDir Path tmp) throws IOException {
      MavenSession session = sessionWithAgentJar(tmp);
      MavenProject p = projectWithPlugins(plugin(SUREFIRE, null));

      ConfigInjector.inject(p, session, tmp.resolve(AGENT_JAR_NAME).toString());

      Xpp3Dom cfg = (Xpp3Dom) p.getBuild().getPluginsAsMap().get(SUREFIRE).getConfiguration();
      assertNotNull(cfg, "agent must be wired when forking is enabled");
      assertTrue(cfg.getChild("argLine").getValue().contains("-javaagent:"));
      assertNotNull(cfg.getChild("additionalClasspathElements"));
    }

    @Test
    void definesEmptyArgLinePropertyDefaultSoLateBindingResolves(@TempDir Path tmp)
        throws IOException {
      // The injected argLine ends in @{argLine}; surefire passes an unresolved
      // late placeholder to the JVM verbatim and crashes the fork. inject must
      // seed an empty `argLine` property so the placeholder resolves when no
      // coverage agent is present.
      MavenSession session = sessionWithAgentJar(tmp);
      MavenProject p = projectWithPlugins(plugin(SUREFIRE, null));
      assertFalse(p.getProperties().containsKey("argLine"));

      ConfigInjector.inject(p, session, tmp.resolve(AGENT_JAR_NAME).toString());

      assertEquals("", p.getProperties().getProperty("argLine"));
    }

    @Test
    void doesNotOverwriteExistingArgLineProperty(@TempDir Path tmp) throws IOException {
      // A user- or plugin-set argLine property must survive — we only provide a
      // default when none exists.
      MavenSession session = sessionWithAgentJar(tmp);
      MavenProject p = projectWithPlugins(plugin(SUREFIRE, null));
      p.getProperties().setProperty("argLine", "-Dpreset=1");

      ConfigInjector.inject(p, session, tmp.resolve(AGENT_JAR_NAME).toString());

      assertEquals("-Dpreset=1", p.getProperties().getProperty("argLine"));
    }

    @Test
    void doesNotDefineArgLinePropertyWhenNothingInjected(@TempDir Path tmp) throws IOException {
      // forkCount=0 suppresses injection, so we emit no @{argLine} and must not
      // pollute the project with an argLine property.
      MavenSession session = sessionWithAgentJar(tmp);
      MavenProject p = projectWithPlugins(plugin(SUREFIRE, configWith("forkCount", "0")));

      ConfigInjector.inject(p, session, tmp.resolve(AGENT_JAR_NAME).toString());

      assertFalse(p.getProperties().containsKey("argLine"));
    }

    @Test
    void doesNotWireSurefireWhenForkCountZero(@TempDir Path tmp) throws IOException {
      MavenSession session = sessionWithAgentJar(tmp);
      Plugin sf = plugin(SUREFIRE, configWith("forkCount", "0"));
      MavenProject p = projectWithPlugins(sf);

      ConfigInjector.inject(p, session, tmp.resolve(AGENT_JAR_NAME).toString());

      Xpp3Dom cfg = (Xpp3Dom) p.getBuild().getPluginsAsMap().get(SUREFIRE).getConfiguration();
      assertNull(cfg.getChild("argLine"), "forkCount=0 must suppress agent injection");
      assertNull(cfg.getChild("additionalClasspathElements"));
    }

    @Test
    void doesNotWireSurefireWhenForkModeNone(@TempDir Path tmp) throws IOException {
      // Regression: ConfigInjector previously accepted only forkMode=never and
      // would have wired the agent here, diverging from SelectionEngine. Both
      // now route through ForkConfig, which treats none == never.
      MavenSession session = sessionWithAgentJar(tmp);
      Plugin sf = plugin(SUREFIRE, configWith("forkMode", "none"));
      MavenProject p = projectWithPlugins(sf);

      ConfigInjector.inject(p, session, tmp.resolve(AGENT_JAR_NAME).toString());

      Xpp3Dom cfg = (Xpp3Dom) p.getBuild().getPluginsAsMap().get(SUREFIRE).getConfiguration();
      assertNull(cfg.getChild("argLine"), "forkMode=none must suppress agent injection");
    }

    @Test
    void doesNotWireFailsafeWhenItSelectionDisabledByDefault(@TempDir Path tmp) throws IOException {
      MavenSession session = sessionWithAgentJar(tmp);
      MavenProject p = projectWithPlugins(plugin(SUREFIRE, null), plugin(FAILSAFE, null));

      ConfigInjector.inject(p, session, tmp.resolve(AGENT_JAR_NAME).toString());

      Xpp3Dom surefire = (Xpp3Dom) p.getBuild().getPluginsAsMap().get(SUREFIRE).getConfiguration();
      assertNotNull(surefire, "unit selection must still wire Surefire");
      assertTrue(surefire.getChild("argLine").getValue().contains("-javaagent:"));
      assertNull(
          p.getBuild().getPluginsAsMap().get(FAILSAFE).getConfiguration(),
          "Failsafe must be left untouched when IT selection is off");
    }

    @Test
    void wiresFailsafeWhenItSelectionEnabled(@TempDir Path tmp) throws IOException {
      MavenSession session = sessionWithAgentJar(tmp, true);
      MavenProject p = projectWithPlugins(plugin(FAILSAFE, null));

      ConfigInjector.inject(p, session, tmp.resolve(AGENT_JAR_NAME).toString());

      Xpp3Dom cfg = (Xpp3Dom) p.getBuild().getPluginsAsMap().get(FAILSAFE).getConfiguration();
      assertNotNull(cfg, "Failsafe must be wired once IT selection is enabled");
      assertTrue(cfg.getChild("argLine").getValue().contains("-javaagent:"));
      assertEquals("${cull.selected.tests.it}", cfg.getChild("test").getValue());
      assertNotNull(cfg.getChild("additionalClasspathElements"));
    }

    private static final String AGENT_JAR_NAME = "cull-agent.jar";

    private MavenSession sessionWithAgentJar(Path tmp) throws IOException {
      return sessionWithAgentJar(tmp, false);
    }

    private MavenSession sessionWithAgentJar(Path tmp, boolean itEnabled) throws IOException {
      Path jar = tmp.resolve(AGENT_JAR_NAME);
      try (JarOutputStream ignored = new JarOutputStream(Files.newOutputStream(jar))) {
        // a valid, empty jar is enough: resolveListenerJar just copies the
        // (absent) listener entries into an equally empty listener jar.
      }
      Properties user = new Properties();
      user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
      if (itEnabled) {
        user.setProperty(CullProperties.IT_ENABLED, "true");
      }
      return TestSessionFactory.createSession(user, new Properties());
    }
  }

  // -- helpers --

  private static String norm(Path p) {
    return p.toAbsolutePath().toString().replace('\\', '/');
  }

  private static String child(Xpp3Dom dom, String name) {
    Xpp3Dom c = dom.getChild(name);
    return c == null ? null : c.getValue();
  }

  private static boolean containsElementValue(Xpp3Dom acp, String value) {
    for (Xpp3Dom el : acp.getChildren("additionalClasspathElement")) {
      if (value.equals(el.getValue())) {
        return true;
      }
    }
    return false;
  }

  private static Xpp3Dom config() {
    return new Xpp3Dom("configuration");
  }

  private static Xpp3Dom configWith(String childName, String value) {
    Xpp3Dom cfg = config();
    addChild(cfg, childName, value);
    return cfg;
  }

  private static void addChild(Xpp3Dom parent, String name, String value) {
    Xpp3Dom c = new Xpp3Dom(name);
    c.setValue(value);
    parent.addChild(c);
  }

  private static Dependency dep(String groupId, String artifactId) {
    Dependency d = new Dependency();
    d.setGroupId(groupId);
    d.setArtifactId(artifactId);
    return d;
  }

  private static MavenProject projectWithDeps(Dependency... deps) {
    MavenProject p = new MavenProject();
    for (Dependency d : deps) {
      p.getModel().addDependency(d);
    }
    return p;
  }

  private static Plugin plugin(String gav, Xpp3Dom configuration) {
    String[] parts = gav.split(":");
    Plugin plugin = new Plugin();
    plugin.setGroupId(parts[0]);
    plugin.setArtifactId(parts[1]);
    plugin.setVersion("3.2.5");
    if (configuration != null) {
      plugin.setConfiguration(configuration);
    }
    return plugin;
  }

  private static MavenProject projectWithPlugins(Plugin... plugins) {
    MavenProject p = new MavenProject();
    p.setGroupId("io.pjmartos.cull.test");
    p.setArtifactId("injecttarget");
    p.setVersion("1.0");
    Build b = new Build();
    for (Plugin pl : plugins) {
      b.addPlugin(pl);
    }
    p.setBuild(b);
    b.flushPluginMap();
    return p;
  }
}
