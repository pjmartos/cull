package io.pjmartos.cull.plugin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.extension.CullProperties;
import io.pjmartos.cull.extension.CullSession;
import io.pjmartos.cull.extension.CullSessionRegistry;
import io.pjmartos.cull.extension.SelectionOutcome;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies how {@link CullSelectMojo} and {@link CullSelectItMojo} translate a {@link
 * SelectionOutcome} into published Maven project properties, covering both the wildcard and the
 * concrete-selection shapes. Both mojos publish a resolvable observations dir even on a degraded
 * run (falling back to the JVM temp dir) so the injected {@code ${cull.observations.dir[.it]}}
 * placeholder never reaches the agent unresolved.
 */
class MojoPropertyPublicationTest {

  private MavenProject registeredProject;
  private boolean registeredIntegration;

  @AfterEach
  void releaseAnyRegisteredSession() {
    if (registeredProject != null) {
      CullSession cs = CullSessionRegistry.remove(registeredProject, registeredIntegration);
      if (cs != null) {
        cs.rollback();
      }
      registeredProject = null;
    }
  }

  @Test
  void selectMojoPublishesWildcardWhenCullDisabled() throws Exception {
    CullSelectMojo mojo = new CullSelectMojo();
    MavenProject project = new MavenProject();
    inject(mojo, "project", project);
    inject(mojo, "session", disabledSession());

    mojo.execute();

    Properties p = project.getProperties();
    assertEquals("*", p.getProperty(CullProperties.SELECTED_TESTS));
    assertEquals(
        System.getProperty("java.io.tmpdir"),
        p.getProperty("cull.observations.dir"),
        "with no staging dir the unit mojo falls back to the JVM temp dir");
    assertEquals("n/a", p.getProperty(CullProperties.SESSION_ID));
  }

  @Test
  void selectItMojoPublishesWildcardAndFallsBackObservationsDir() throws Exception {
    CullSelectItMojo mojo = new CullSelectItMojo();
    MavenProject project = new MavenProject();
    inject(mojo, "project", project);
    inject(mojo, "session", disabledSession());

    mojo.execute();

    Properties p = project.getProperties();
    assertEquals("*", p.getProperty(CullProperties.SELECTED_TESTS + ".it"));
    assertEquals("n/a", p.getProperty(CullProperties.SESSION_ID + ".it"));
    assertEquals(
        System.getProperty("java.io.tmpdir"),
        p.getProperty("cull.observations.dir.it"),
        "with no staging dir the IT mojo falls back to the JVM temp dir");
  }

  @Test
  void selectMojoPublishesConcreteSelectionAndStagingDir(@TempDir Path tmp) throws Exception {
    CullSelectMojo mojo = new CullSelectMojo();
    MavenProject project = fullProject(tmp, "select-ut");
    inject(mojo, "project", project);
    inject(mojo, "session", cacheSession(tmp));
    registeredProject = project;
    registeredIntegration = false;

    mojo.execute();

    Properties p = project.getProperties();
    assertEquals(
        SelectionOutcome.NO_MATCH_PATTERN,
        p.getProperty(CullProperties.SELECTED_TESTS),
        "no test classes and no prior graph selects nothing, not the wildcard");
    String obs = p.getProperty("cull.observations.dir");
    assertNotNull(obs);
    assertTrue(Files.isDirectory(Path.of(obs)), "the published staging dir must exist");
    String sessionId = p.getProperty(CullProperties.SESSION_ID);
    assertNotNull(sessionId);
    assertNotEquals("n/a", sessionId);
    assertNotNull(CullSessionRegistry.get(project, false), "a session must have been registered");
  }

  @Test
  void selectItMojoPublishesWildcardWhenItSelectionDisabledByDefault() throws Exception {
    CullSelectItMojo mojo = new CullSelectItMojo();
    MavenProject project = new MavenProject();
    inject(mojo, "project", project);
    inject(mojo, "session", new SessionStub(new Properties(), new Properties()));

    mojo.execute();

    Properties p = project.getProperties();
    assertEquals("*", p.getProperty(CullProperties.SELECTED_TESTS + ".it"));
    assertEquals("n/a", p.getProperty(CullProperties.SESSION_ID + ".it"));
    assertEquals(System.getProperty("java.io.tmpdir"), p.getProperty("cull.observations.dir.it"));
    assertFalse(p.containsKey(CullProperties.SELECTED_TESTS), "IT bypass must not touch UT keys");
  }

  @Test
  void selectItMojoPublishesConcreteSelectionAndStagingDir(@TempDir Path tmp) throws Exception {
    CullSelectItMojo mojo = new CullSelectItMojo();
    MavenProject project = fullProject(tmp, "select-it");
    inject(mojo, "project", project);
    inject(mojo, "session", cacheSessionItEnabled(tmp));
    registeredProject = project;
    registeredIntegration = true;

    mojo.execute();

    Properties p = project.getProperties();
    assertEquals(
        SelectionOutcome.NO_MATCH_PATTERN, p.getProperty(CullProperties.SELECTED_TESTS + ".it"));
    String obs = p.getProperty("cull.observations.dir.it");
    assertNotNull(obs, "with a staging dir the IT mojo does publish the observations dir");
    assertTrue(Files.isDirectory(Path.of(obs)));
    assertNotEquals("n/a", p.getProperty(CullProperties.SESSION_ID + ".it"));
    assertFalse(p.containsKey(CullProperties.SELECTED_TESTS), "IT mojo must not touch UT keys");
    assertNotNull(CullSessionRegistry.get(project, true));
  }

  private static MavenSession disabledSession() {
    Properties user = new Properties();
    user.setProperty(CullProperties.DISABLED, "true");
    return new SessionStub(user, new Properties());
  }

  private static MavenSession cacheSession(Path tmp) {
    Properties user = new Properties();
    user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
    return new SessionStub(user, new Properties());
  }

  private static MavenSession cacheSessionItEnabled(Path tmp) {
    Properties user = new Properties();
    user.setProperty(CullProperties.CACHE_DIR, tmp.resolve("cache").toString());
    user.setProperty(CullProperties.IT_ENABLED, "true");
    return new SessionStub(user, new Properties());
  }

  private static MavenProject fullProject(Path tmp, String artifact) throws IOException {
    MavenProject p = new MavenProject();
    p.setGroupId("io.pjmartos.cull.test");
    p.setArtifactId(artifact);
    p.setVersion("1.0");
    Path base = Files.createDirectories(tmp.resolve(artifact));
    Path pom = Files.writeString(base.resolve("pom.xml"), "<project/>");
    p.setFile(pom.toFile());
    Build b = new Build();
    Path target = Files.createDirectories(base.resolve("target"));
    b.setDirectory(target.toString());
    b.setOutputDirectory(target.resolve("classes").toString());
    b.setTestOutputDirectory(target.resolve("test-classes").toString());
    p.setBuild(b);
    return p;
  }

  private static void inject(AbstractMojo mojo, String field, Object value) throws Exception {
    Field f = mojo.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(mojo, value);
  }

  @SuppressWarnings("deprecation")
  private static final class SessionStub extends MavenSession {
    private final Properties userProps;
    private final Properties sysProps;
    private final List<MavenProject> projects = new ArrayList<>();

    SessionStub(Properties userProps, Properties sysProps) {
      super(
          null,
          new DefaultMavenExecutionRequest(),
          new DefaultMavenExecutionResult(),
          new ArrayList<>());
      this.userProps = userProps;
      this.sysProps = sysProps;
    }

    @Override
    public Properties getUserProperties() {
      return userProps;
    }

    @Override
    public Properties getSystemProperties() {
      return sysProps;
    }

    @Override
    public List<MavenProject> getProjects() {
      return projects;
    }
  }
}
