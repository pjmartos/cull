package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Covers the {@code .cullignore} parser, the always-on strip of cull's own injected
 * Surefire/Failsafe nodes, subtree pruning at arbitrary nesting, and the policy fingerprint.
 */
class ChecksumExclusionsTest {

  private static final String SUREFIRE = "org.apache.maven.plugins:maven-surefire-plugin";

  @Test
  void selfInjectionStripRemovesEveryCullNodeButKeepsUserContent(@TempDir Path tmp) {
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    cfg.addChild(
        leaf(
            "argLine",
            "-javaagent:/home/ci/.cull/agents/9/cull-agent-9.jar=observations=${cull.observations.dir} -Xmx1g"));
    Xpp3Dom acp = new Xpp3Dom("additionalClasspathElements");
    acp.addChild(leaf("additionalClasspathElement", "/c/.cull/agents/9/cull-agent-listener.jar"));
    acp.addChild(leaf("additionalClasspathElement", "/user/extra.jar"));
    cfg.addChild(acp);
    Xpp3Dom sys = new Xpp3Dom("systemPropertyVariables");
    sys.addChild(leaf("cull.observations.dir", "x"));
    sys.addChild(leaf("cull.session.id", "y"));
    sys.addChild(leaf("user.flag", "keep"));
    cfg.addChild(sys);
    Xpp3Dom props = new Xpp3Dom("properties");
    props.addChild(property("listener", "io.pjmartos.cull.agent.listeners.CullJunit4Listener"));
    props.addChild(property("other", "value"));
    cfg.addChild(props);
    cfg.addChild(leaf("test", "${cull.selected.tests}"));
    cfg.addChild(leaf("failIfNoTests", "false"));

    load(tmp).prune(SUREFIRE, cfg);

    assertEquals("-Xmx1g", cfg.getChild("argLine").getValue());
    Xpp3Dom acpOut = cfg.getChild("additionalClasspathElements");
    assertEquals(1, acpOut.getChildCount());
    assertEquals("/user/extra.jar", acpOut.getChild(0).getValue());
    Xpp3Dom sysOut = cfg.getChild("systemPropertyVariables");
    assertEquals(1, sysOut.getChildCount());
    assertEquals("user.flag", sysOut.getChild(0).getName());
    Xpp3Dom propsOut = cfg.getChild("properties");
    assertEquals(1, propsOut.getChildCount());
    assertEquals("other", propsOut.getChild(0).getChild("name").getValue());
    assertNull(cfg.getChild("test"), "cull's selected-tests placeholder must be stripped");
    assertNull(cfg.getChild("failIfNoTests"));
  }

  @Test
  void argLineConsistingOnlyOfCullSegmentIsRemovedEntirely(@TempDir Path tmp) {
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    cfg.addChild(
        leaf("argLine", "-javaagent:/x/cull-agent-1.jar=observations=${cull.observations.dir}"));
    load(tmp).prune(SUREFIRE, cfg);
    assertNull(
        cfg.getChild("argLine"), "an argLine that was purely cull's must collapse to absent");
  }

  @Test
  void selfInjectionStripRemovesCullDelegatedArgLineRef(@TempDir Path tmp) {
    // cull appends @{argLine} after its agent segment to delegate to a
    // late-bound coverage-agent property (the JaCoCo idiom). That marker is
    // cull's own injection and must be stripped too, so installing cull does
    // not rotate the checksum for a project that previously had user flags.
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    cfg.addChild(
        leaf(
            "argLine",
            "-javaagent:/x/cull-agent-1.jar=observations=${cull.observations.dir} @{argLine} -Xmx1g"));
    load(tmp).prune(SUREFIRE, cfg);
    assertEquals("-Xmx1g", cfg.getChild("argLine").getValue());
  }

  @Test
  void cullSegmentPlusDelegatedArgLineRefCollapsesToAbsent(@TempDir Path tmp) {
    // The case-A injection (project had no <argLine>): cull writes only its
    // own segment plus @{argLine}. Stripping both leaves nothing, so the
    // checksum is identical to a project that never had cull installed.
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    cfg.addChild(
        leaf(
            "argLine",
            "-javaagent:/x/cull-agent-1.jar=observations=${cull.observations.dir} @{argLine}"));
    load(tmp).prune(SUREFIRE, cfg);
    assertNull(
        cfg.getChild("argLine"),
        "cull segment plus its delegated @{argLine} must collapse to absent");
  }

  @Test
  void selfInjectionStripHandlesAgentPathWithSpaces(@TempDir Path tmp) {
    // A Windows user home such as "C:\Users\First Last" puts a space inside the
    // agent jar path, before the "cull-agent" marker. The strip must still
    // remove the whole cull segment so cull does not rotate its own cache key.
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    cfg.addChild(
        leaf(
            "argLine",
            "-javaagent:C:/Users/First Last/.m2/repository/io/pjmartos/cull/cull-agent/1/cull-agent-1.jar=observations=${cull.observations.dir} -Xmx1g -Dx=y"));
    load(tmp).prune(SUREFIRE, cfg);
    assertEquals("-Xmx1g -Dx=y", cfg.getChild("argLine").getValue());
  }

  @Test
  void selfInjectionStripRemovesSpacedAgentPathWithNoUserRemainder(@TempDir Path tmp) {
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    cfg.addChild(
        leaf(
            "argLine",
            "-javaagent:C:/Program Files/cull/cull-agent-1.jar=observations=${cull.observations.dir}"));
    load(tmp).prune(SUREFIRE, cfg);
    assertNull(
        cfg.getChild("argLine"),
        "a spaced-path argLine that was purely cull's must collapse to absent");
  }

  @Test
  void selfInjectionNotAppliedToUnrelatedPlugins(@TempDir Path tmp) {
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    cfg.addChild(leaf("argLine", "-javaagent:/x/cull-agent-1.jar something"));
    load(tmp).prune("com.example:some-plugin", cfg);
    assertNotNull(cfg.getChild("argLine"), "the strip is scoped to Surefire/Failsafe only");
    assertEquals("-javaagent:/x/cull-agent-1.jar something", cfg.getChild("argLine").getValue());
  }

  @Test
  void userPatternPrunesSubtreeAtArbitraryNesting(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve(".cullignore"), "com.x:y#a.**.nonce\n");
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom a = new Xpp3Dom("a");
    Xpp3Dom b = new Xpp3Dom("b");
    Xpp3Dom deep = new Xpp3Dom("nonce");
    deep.addChild(leaf("inner", "volatile"));
    b.addChild(deep);
    b.addChild(leaf("keep", "stable"));
    a.addChild(b);
    cfg.addChild(a);

    load(tmp).prune("com.x:y", cfg);

    Xpp3Dom bOut = cfg.getChild("a").getChild("b");
    assertNull(bOut.getChild("nonce"), "the matched subtree must be gone");
    assertNotNull(bOut.getChild("keep"), "siblings must survive");
  }

  @Test
  void escapedPeriodsAreHonoured(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve(".cullignore"), "com.x:y#a\\.b\\.nonce\n");
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom a = new Xpp3Dom("a");
    Xpp3Dom b = new Xpp3Dom("b");
    Xpp3Dom deep = new Xpp3Dom("nonce");
    Xpp3Dom aBNonce = new Xpp3Dom("a.b.nonce");
    b.addChild(deep);
    a.addChild(b);
    cfg.addChild(a);
    cfg.addChild(aBNonce);

    load(tmp).prune("com.x:y", cfg);

    Xpp3Dom bOut = cfg.getChild("a").getChild("b");
    assertNotNull(bOut.getChild("nonce"), "the dotted path subtree must survive");
    assertNull(cfg.getChild("a.b.nonce"), "the matched subtree must be gone");
  }

  @Test
  void singleStarMatchesExactlyOneLevel(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve(".cullignore"), "com.x:y#a.*.nonce\n");
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom a = new Xpp3Dom("a");
    Xpp3Dom b = new Xpp3Dom("b");
    b.addChild(new Xpp3Dom("nonce")); // a/b/nonce  -> matches a.*.nonce
    Xpp3Dom c = new Xpp3Dom("c");
    Xpp3Dom d = new Xpp3Dom("d");
    d.addChild(new Xpp3Dom("nonce")); // a/c/d/nonce -> does NOT match a.*.nonce
    c.addChild(d);
    a.addChild(b);
    a.addChild(c);
    cfg.addChild(a);

    load(tmp).prune("com.x:y", cfg);

    assertNull(cfg.getChild("a").getChild("b").getChild("nonce"));
    assertNotNull(cfg.getChild("a").getChild("c").getChild("d").getChild("nonce"));
  }

  @Test
  void wildcardPluginCoordinatesMatchAnyPlugin(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve(".cullignore"), "*:*#timestamp\n");
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    cfg.addChild(leaf("timestamp", "2026-05-17T13:00:00Z"));
    cfg.addChild(leaf("keep", "v"));
    load(tmp).prune("any.group:any-artifact", cfg);
    assertNull(cfg.getChild("timestamp"));
    assertNotNull(cfg.getChild("keep"));
  }

  @Test
  void commentsBlankAndInvalidLinesAreIgnored(@TempDir Path tmp) throws IOException {
    Files.writeString(
        tmp.resolve(".cullignore"),
        "# a comment\n\n   \nnoseparator\ncom.x:y#\ncom.x:y#real.path\n");
    ChecksumExclusions ex = load(tmp);
    Xpp3Dom cfg = new Xpp3Dom("configuration");
    Xpp3Dom real = new Xpp3Dom("real");
    real.addChild(new Xpp3Dom("path"));
    cfg.addChild(real);
    ex.prune("com.x:y", cfg);
    assertNull(cfg.getChild("real").getChild("path"), "only the one valid pattern applies");
  }

  @Test
  void fingerprintIsStableAndChangesWithPolicy(@TempDir Path tmp) throws IOException {
    byte[] empty1 = load(tmp).fingerprint();
    byte[] empty2 = load(tmp).fingerprint();
    assertArrayEquals(empty1, empty2, "absent .cullignore is a constant policy");

    Files.writeString(tmp.resolve(".cullignore"), "com.x:y#argLine\n");
    byte[] withOne = load(tmp).fingerprint();
    assertFalse(
        Arrays.equals(empty1, withOne), "adding an exclusion must change the policy fingerprint");

    Files.writeString(tmp.resolve(".cullignore"), "com.x:y#argLine\ncom.x:z#test\n");
    byte[] withTwo = load(tmp).fingerprint();
    assertFalse(Arrays.equals(withOne, withTwo));
  }

  @Test
  void fingerprintIsOrderInsensitive(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve(".cullignore"), "com.x:y#argLine\ncom.x:z#test\n");
    byte[] ab = load(tmp).fingerprint();
    Files.writeString(tmp.resolve(".cullignore"), "com.x:z#test\ncom.x:y#argLine\n");
    byte[] ba = load(tmp).fingerprint();
    assertArrayEquals(ab, ba, "pattern order in the file must not matter");
  }

  private static ChecksumExclusions load(Path baseDir) {
    MavenProject p = new MavenProject();
    p.setFile(baseDir.resolve("pom.xml").toFile());
    return ChecksumExclusions.load(p, null);
  }

  private static Xpp3Dom leaf(String name, String value) {
    Xpp3Dom n = new Xpp3Dom(name);
    n.setValue(value);
    return n;
  }

  private static Xpp3Dom property(String name, String value) {
    Xpp3Dom prop = new Xpp3Dom("property");
    prop.addChild(leaf("name", name));
    prop.addChild(leaf("value", value));
    return prop;
  }
}
