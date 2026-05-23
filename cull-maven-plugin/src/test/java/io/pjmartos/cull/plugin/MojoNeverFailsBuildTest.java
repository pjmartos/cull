package io.pjmartos.cull.plugin;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.pjmartos.cull.extension.CullProperties;
import java.lang.reflect.Field;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.project.MavenProject;
import org.junit.jupiter.api.Test;

/**
 * The hard invariant from the design is that cull never fails the build. Both selection mojos must
 * swallow any failure in the selection engine and degrade to running all tests (wildcard), instead
 * of letting the exception abort the user's Maven build.
 */
class MojoNeverFailsBuildTest {

  @Test
  void selectMojoDegradesToWildcardInsteadOfFailingBuild() throws Exception {
    CullSelectMojo mojo = new CullSelectMojo();
    MavenProject project = new MavenProject();
    inject(mojo, "project", project);
    // session left null on purpose: SelectionEngine.runFor dereferences the session immediately
    // and throws. execute() must not propagate that.
    assertDoesNotThrow(mojo::execute);
    assertEquals("*", project.getProperties().getProperty(CullProperties.SELECTED_TESTS));
  }

  @Test
  void selectItMojoDegradesToWildcardInsteadOfFailingBuild() throws Exception {
    CullSelectItMojo mojo = new CullSelectItMojo();
    MavenProject project = new MavenProject();
    inject(mojo, "project", project);
    assertDoesNotThrow(mojo::execute);
    assertEquals("*", project.getProperties().getProperty(CullProperties.SELECTED_TESTS + ".it"));
  }

  private static void inject(AbstractMojo mojo, String field, Object value) throws Exception {
    Field f = mojo.getClass().getDeclaredField(field);
    f.setAccessible(true);
    f.set(mojo, value);
  }
}
