package io.pjmartos.cull.extension;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.DefaultMavenExecutionResult;
import org.apache.maven.execution.MavenExecutionResult;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.project.MavenProject;

/** Creates lightweight MavenSession stubs for unit tests. */
final class TestSessionFactory {

  private TestSessionFactory() {}

  static MavenSession createSession(Properties userProperties, Properties systemProperties) {
    return createSession(
        userProperties, systemProperties, new ArrayList<>(), new DefaultMavenExecutionResult());
  }

  @SuppressWarnings("deprecation")
  static MavenSession createSession(
      Properties userProperties,
      Properties systemProperties,
      List<MavenProject> projects,
      MavenExecutionResult result) {
    return new SessionStub(userProperties, systemProperties, projects, result);
  }

  @SuppressWarnings("deprecation")
  private static final class SessionStub extends MavenSession {
    private final Properties userProps;
    private final Properties sysProps;
    private final List<MavenProject> projects;

    SessionStub(
        Properties userProperties,
        Properties systemProperties,
        List<MavenProject> projects,
        MavenExecutionResult result) {
      super(null, new DefaultMavenExecutionRequest(), result, projects);
      this.userProps = userProperties != null ? userProperties : new Properties();
      this.sysProps = systemProperties != null ? systemProperties : new Properties();
      this.projects = projects != null ? projects : new ArrayList<>();
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
