package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.model.Build;
import org.apache.maven.model.Plugin;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;

/**
 * Resolution order (property &gt; Surefire/Failsafe includes &gt; built-in heuristic) and the
 * Surefire-style glob translation used by {@link TestNameFilter}.
 */
class TestNameFilterTest {

  @Test
  void builtinHeuristicMatchesHistoricalUnitNames() {
    TestNameFilter f = TestNameFilter.forProject(plainProject(), session(new Properties()), false);
    assertTrue(f.matches("com.example.FooTest", "com/example/FooTest"));
    assertTrue(f.matches("com.example.FooTests", "com/example/FooTests"));
    assertFalse(f.matches("com.example.FooSpec", "com/example/FooSpec"));
    assertFalse(f.matches("com.example.Helper", "com/example/Helper"));
  }

  @Test
  void builtinHeuristicMatchesHistoricalItNames() {
    TestNameFilter f = TestNameFilter.forProject(plainProject(), session(new Properties()), true);
    assertTrue(f.matches("com.example.FooIT", "com/example/FooIT"));
    assertTrue(f.matches("com.example.FooITCase", "com/example/FooITCase"));
    assertFalse(f.matches("com.example.FooTest", "com/example/FooTest"));
  }

  @Test
  void explicitPropertyOverridesHeuristic() {
    Properties user = new Properties();
    user.setProperty(CullProperties.TEST_INCLUDES, "**/*Spec.java");
    TestNameFilter f = TestNameFilter.forProject(plainProject(), session(user), false);
    assertTrue(f.matches("com.example.FooSpec", "com/example/FooSpec"));
    assertFalse(
        f.matches("com.example.FooTest", "com/example/FooTest"),
        "an explicit include list replaces the heuristic entirely");
  }

  @Test
  void derivesFromSurefireIncludesAndExcludes() {
    Plugin surefire = new Plugin();
    surefire.setGroupId("org.apache.maven.plugins");
    surefire.setArtifactId("maven-surefire-plugin");
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom includes = new Xpp3Dom("includes");
    includes.addChild(leaf("include", "**/Check*.java"));
    Xpp3Dom excludes = new Xpp3Dom("excludes");
    excludes.addChild(leaf("exclude", "**/CheckAbstract*.java"));
    cfg.addChild(includes);
    cfg.addChild(excludes);
    surefire.setConfiguration(cfg);
    MavenProject p = plainProject();
    p.getBuild().addPlugin(surefire);
    p.getBuild().flushPluginMap();

    TestNameFilter f = TestNameFilter.forProject(p, session(new Properties()), false);

    assertTrue(f.matches("com.example.CheckThing", "com/example/CheckThing"));
    assertFalse(
        f.matches("com.example.CheckAbstractBase", "com/example/CheckAbstractBase"),
        "an excluded pattern wins over an include");
    assertFalse(
        f.matches("com.example.FooTest", "com/example/FooTest"),
        "deriving from Surefire includes replaces the heuristic");
  }

  @Test
  void globTranslationHonorsSeparatorSemantics() {
    java.util.regex.Pattern p =
        java.util.regex.Pattern.compile(TestNameFilter.globToRegex("**/*Test.java"));
    assertTrue(p.matcher("com/example/FooTest").matches());
    assertTrue(p.matcher("FooTest").matches(), "**/ must also match zero directories");
    assertFalse(p.matcher("com/example/Foo").matches());

    java.util.regex.Pattern single =
        java.util.regex.Pattern.compile(TestNameFilter.globToRegex("*IT.java"));
    assertTrue(single.matcher("FooIT").matches());
    assertFalse(single.matcher("pkg/FooIT").matches(), "* must not span the path separator");

    java.util.regex.Pattern regex =
        java.util.regex.Pattern.compile(TestNameFilter.globToRegex("%regex[.*Foo]"));
    assertTrue(regex.matcher("a/b/Foo").matches());
  }

  private static MavenProject plainProject() {
    MavenProject p = new MavenProject();
    p.setGroupId("com.example");
    p.setArtifactId("mod");
    p.setVersion("1.0");
    p.setBuild(new Build());
    return p;
  }

  private static MavenSession session(Properties user) {
    return TestSessionFactory.createSession(user, new Properties());
  }

  private static Xpp3Dom leaf(String name, String value) {
    Xpp3Dom n = new Xpp3Dom(name);
    n.setValue(value);
    return n;
  }
}
