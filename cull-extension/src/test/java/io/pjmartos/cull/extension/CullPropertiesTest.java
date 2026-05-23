package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Properties;
import org.apache.maven.execution.MavenSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link CullProperties} — property resolution from user/system properties and defaults.
 */
class CullPropertiesTest {

  private MavenSession session;
  private Properties userProps;
  private Properties sysProps;

  @BeforeEach
  void setUp() {
    userProps = new Properties();
    sysProps = new Properties();
    session = TestSessionFactory.createSession(userProps, sysProps);
  }

  @Test
  void disabledByDefault() {
    assertFalse(CullProperties.isDisabled(session));
  }

  @Test
  void disabledWhenUserPropertySet() {
    userProps.setProperty(CullProperties.DISABLED, "true");
    assertTrue(CullProperties.isDisabled(session));
  }

  @Test
  void disabledWhenSystemPropertySet() {
    sysProps.setProperty(CullProperties.DISABLED, "true");
    assertTrue(CullProperties.isDisabled(session));
  }

  @Test
  void disabledCaseInsensitive() {
    userProps.setProperty(CullProperties.DISABLED, "TRUE");
    assertTrue(CullProperties.isDisabled(session));
    userProps.setProperty(CullProperties.DISABLED, "True");
    assertTrue(CullProperties.isDisabled(session));
  }

  @Test
  void notDisabledForNonTrueValues() {
    userProps.setProperty(CullProperties.DISABLED, "false");
    assertFalse(CullProperties.isDisabled(session));
    userProps.setProperty(CullProperties.DISABLED, "yes");
    assertFalse(CullProperties.isDisabled(session));
    userProps.setProperty(CullProperties.DISABLED, "1");
    assertFalse(CullProperties.isDisabled(session));
  }

  @Test
  void userPropertyTakesPrecedenceOverSystem() {
    userProps.setProperty(CullProperties.DISABLED, "false");
    sysProps.setProperty(CullProperties.DISABLED, "true");
    assertFalse(CullProperties.isDisabled(session));
  }

  @Test
  void fallbackRunAllByDefault() {
    assertFalse(CullProperties.isFallbackRunAll(session));
  }

  @Test
  void fallbackRunAllWhenSet() {
    userProps.setProperty(CullProperties.FALLBACK_RUN_ALL, "true");
    assertTrue(CullProperties.isFallbackRunAll(session));
  }

  @Test
  void ioHooksDisabledByDefault() {
    assertFalse(CullProperties.ioHooksDisabled(session));
  }

  @Test
  void ioHooksDisabledWhenSet() {
    userProps.setProperty(CullProperties.IO_HOOKS_DISABLED, "true");
    assertTrue(CullProperties.ioHooksDisabled(session));
  }

  @Test
  void cacheRetentionDefaultIsFive() {
    assertEquals(5, CullProperties.cacheRetention(session));
  }

  @Test
  void cacheRetentionParsesUserValue() {
    userProps.setProperty(CullProperties.CACHE_RETENTION, "10");
    assertEquals(10, CullProperties.cacheRetention(session));
  }

  @Test
  void cacheRetentionFallsBackToFiveOnParseError() {
    userProps.setProperty(CullProperties.CACHE_RETENTION, "not-a-number");
    assertEquals(5, CullProperties.cacheRetention(session));
  }

  @Test
  void cacheRetentionAcceptsRawSystemProperty() {
    sysProps.setProperty(CullProperties.CACHE_RETENTION, "3");
    assertEquals(3, CullProperties.cacheRetention(session));
  }

  @Test
  void cacheDirectoryDefaultsToHome() {
    Path dir = CullProperties.cacheDirectory(session);
    String expected = System.getProperty("user.home") + "/.cull/cache";
    assertEquals(Path.of(expected), dir);
  }

  @Test
  void cacheDirectoryFromUserProperty() {
    userProps.setProperty(CullProperties.CACHE_DIR, "/custom/cache");
    assertEquals(Path.of("/custom/cache"), CullProperties.cacheDirectory(session));
  }

  @Test
  void observationsRootFromUserProperty() {
    userProps.setProperty(CullProperties.OBSERVATIONS_DIR, "/custom/obs");
    Path root = CullProperties.observationsRoot(session, testProject());
    assertEquals(Path.of("/custom/obs"), root);
  }

  @Test
  void observationsRootDefaultsToBuildDir() {
    Path root = CullProperties.observationsRoot(session, testProject());
    assertTrue(
        root.toString().replace('\\', '/').endsWith("target/cull/observations"),
        "observations dir should end with target/cull/observations, got: " + root);
  }

  @Test
  void defaultCacheDirUsesUserHome() {
    String home = System.getProperty("user.home");
    assertTrue(CullProperties.defaultCacheDir().startsWith(home));
    assertTrue(CullProperties.defaultCacheDir().contains(".cull/cache"));
  }

  @Test
  void systemPropertyUsedWhenUserPropertyNotSet() {
    sysProps.setProperty(CullProperties.CACHE_DIR, "/sys/cache");
    assertEquals(Path.of("/sys/cache"), CullProperties.cacheDirectory(session));
  }

  @Test
  void testIncludesNullUntilConfigured() {
    assertNull(CullProperties.testIncludes(session, false));
    assertNull(CullProperties.testIncludes(session, true));
  }

  @Test
  void testIncludesReadsTheIntegrationAwareKey() {
    userProps.setProperty(CullProperties.TEST_INCLUDES, "**/*Spec");
    userProps.setProperty(CullProperties.IT_INCLUDES, "**/*ITSpec");
    assertEquals("**/*Spec", CullProperties.testIncludes(session, false));
    assertEquals("**/*ITSpec", CullProperties.testIncludes(session, true));
  }

  @Test
  void crossModuleDefaultsToFull() {
    assertEquals(CullProperties.CrossModuleMode.FULL, CullProperties.crossModule(session));
  }

  @Test
  void crossModuleHonorsExplicitValuesCaseInsensitively() {
    userProps.setProperty(CullProperties.CROSS_MODULE, "OFF");
    assertEquals(CullProperties.CrossModuleMode.OFF, CullProperties.crossModule(session));
    userProps.setProperty(CullProperties.CROSS_MODULE, "Full");
    assertEquals(CullProperties.CrossModuleMode.FULL, CullProperties.crossModule(session));
  }

  @Test
  void crossModuleUnknownValueFallsBackToDefault() {
    userProps.setProperty(CullProperties.CROSS_MODULE, "banana");
    assertEquals(CullProperties.CrossModuleMode.FULL, CullProperties.crossModule(session));
  }

  private static org.apache.maven.project.MavenProject testProject() {
    org.apache.maven.project.MavenProject p = new org.apache.maven.project.MavenProject();
    p.getBuild().setDirectory("target");
    return p;
  }
}
