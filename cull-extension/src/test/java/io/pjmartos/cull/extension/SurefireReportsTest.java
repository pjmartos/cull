package io.pjmartos.cull.extension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for {@link SurefireReports} covering pass/fail/no-report scanning across surefire and
 * failsafe directories, test-suite class-name extraction, failed-class collection, and
 * malformed/empty XML edge cases.
 */
class SurefireReportsTest {

  @Test
  void allPassWhenZeroFailuresZeroErrors(@TempDir Path tmp) throws IOException {
    writeReport(tmp, "TEST-com.example.A.xml", failures(0), errors(0));
    writeReport(tmp, "TEST-com.example.B.xml", failures(0), errors(0));
    assertEquals(SurefireReports.Outcome.ALL_PASS, SurefireReports.scan(tmp));
  }

  @Test
  void anyFailureMarksHasFailure(@TempDir Path tmp) throws IOException {
    writeReport(tmp, "TEST-com.example.A.xml", failures(0), errors(0));
    writeReport(tmp, "TEST-com.example.B.xml", failures(1), errors(0));
    assertEquals(SurefireReports.Outcome.HAS_FAILURE, SurefireReports.scan(tmp));
  }

  @Test
  void anyErrorMarksHasFailure(@TempDir Path tmp) throws IOException {
    writeReport(tmp, "TEST-com.example.A.xml", failures(0), errors(2));
    assertEquals(SurefireReports.Outcome.HAS_FAILURE, SurefireReports.scan(tmp));
  }

  @Test
  void emptyDirectoryIsNoReports(@TempDir Path tmp) {
    assertEquals(SurefireReports.Outcome.NO_REPORTS, SurefireReports.scan(tmp));
  }

  @Test
  void missingDirectoryIsNoReports(@TempDir Path tmp) {
    assertEquals(SurefireReports.Outcome.NO_REPORTS, SurefireReports.scan(tmp.resolve("nope")));
  }

  @Test
  void malformedXmlTreatedAsFailure(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("TEST-bad.xml"), "<not-a-testsuite/>");
    assertEquals(SurefireReports.Outcome.HAS_FAILURE, SurefireReports.scan(tmp));
  }

  @Test
  void scansFailsafeDirAlso(@TempDir Path tmp) throws IOException {
    Path sf = Files.createDirectories(tmp.resolve("surefire-reports"));
    Path ff = Files.createDirectories(tmp.resolve("failsafe-reports"));
    writeReport(sf, "TEST-A.xml", failures(0), errors(0));
    writeReport(ff, "TEST-B.xml", failures(1), errors(0));
    assertEquals(SurefireReports.Outcome.HAS_FAILURE, SurefireReports.scan(sf, ff));
  }

  @Test
  void skippedTestsDoNotForceFailure(@TempDir Path tmp) throws IOException {
    String xml =
        "<?xml version=\"1.0\"?>\n"
            + "<testsuite name=\"x\" tests=\"3\" failures=\"0\" errors=\"0\" skipped=\"2\">\n"
            + "</testsuite>\n";
    Files.writeString(tmp.resolve("TEST-com.example.SkippingTest.xml"), xml);
    assertEquals(SurefireReports.Outcome.ALL_PASS, SurefireReports.scan(tmp));
  }

  @Test
  void extractTestSuiteClassNameReturnsNullWhenNoTestsuiteElement() throws IOException {
    Path f =
        createXmlFile(
            Files.createTempDirectory("rpt"), "TEST-NoSuite.xml", "<?xml version=\"1.0\"?><root/>");
    assertNull(SurefireReports.extractTestSuiteClassName(f));
  }

  @Test
  void extractTestSuiteClassNameReturnsNullWhenNameAttributeMissing() throws IOException {
    Path f =
        createXmlFile(
            Files.createTempDirectory("rpt"),
            "TEST-NoName.xml",
            "<?xml version=\"1.0\"?><testsuite failures=\"0\">");
    assertNull(SurefireReports.extractTestSuiteClassName(f));
  }

  @Test
  void extractTestSuiteClassNameReturnsName() throws IOException {
    Path f =
        createXmlFile(
            Files.createTempDirectory("rpt"),
            "TEST-Name.xml",
            "<?xml version=\"1.0\"?><testsuite name=\"com.example.FooTest\" failures=\"0\" errors=\"0\">");
    assertEquals("com.example.FooTest", SurefireReports.extractTestSuiteClassName(f));
  }

  @Test
  void extractTestSuiteClassNameReturnsNullForEmptyFile(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("TEST-Empty.xml");
    Files.writeString(f, "");
    assertNull(SurefireReports.extractTestSuiteClassName(f));
  }

  @Test
  void parseReportFailedReturnsTrueForMissingTestsuite(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("TEST-NoSuite.xml");
    Files.writeString(f, "<?xml version=\"1.0\"?><project/>");
    assertTrue(SurefireReports.parseReportFailed(f));
  }

  @Test
  void parseReportFailedReturnsTrueForMalformedTestsuite(@TempDir Path tmp) throws IOException {
    Path f = tmp.resolve("TEST-Malformed.xml");
    Files.writeString(f, "<?xml version=\"1.0\"?><testsuite");
    assertTrue(SurefireReports.parseReportFailed(f));
  }

  @Test
  void parseReportFailedReturnsTrueForIoException() {
    Path missing = Path.of("/nonexistent/TEST-missing.xml");
    assertTrue(SurefireReports.parseReportFailed(missing));
  }

  @Test
  void scanIgnoresNonTestXmlFiles(@TempDir Path tmp) throws IOException {
    // A real TEST- file plus a non-TEST file; non-TEST should be ignored
    String xml =
        "<?xml version=\"1.0\"?><testsuite name=\"Real\" tests=\"1\" failures=\"0\" errors=\"0\"/>";
    Files.writeString(tmp.resolve("TEST-Real.xml"), xml);
    Files.writeString(tmp.resolve("not-a-test.xml"), "<testsuite name=\"x\" failures=\"1\"/>");
    assertEquals(SurefireReports.Outcome.ALL_PASS, SurefireReports.scan(tmp));
  }

  @Test
  void scanIgnoresXmlWithoutTestsuite(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("TEST-ignored.xml"), "<report/>");
    // The file has TEST- prefix but no <testsuite> — scan should treat as HAS_FAILURE
    // Actually scan delegates to parseReportFailed which returns true for missing testsuite
    assertEquals(SurefireReports.Outcome.HAS_FAILURE, SurefireReports.scan(tmp));
  }

  @Test
  void scanSkipsWalkExceptionGracefully(@TempDir Path tmp) {
    // Pass a file path where a directory is expected — should treat as NO_REPORTS or HAS_FAILURE
    Path filePath = tmp.resolve("not-a-dir.txt");
    assertEquals(SurefireReports.Outcome.NO_REPORTS, SurefireReports.scan(filePath));
  }

  @Test
  void zeroAttributeReturnsFalseWhenAttributeMissing() {
    // Conservative: missing attribute is treated as non-zero (potential failure)
    assertFalse(SurefireReports.zeroAttribute("name=\"x\"", "failures"));
  }

  @Test
  void zeroAttributeReturnsTrueForZero() {
    assertTrue(SurefireReports.zeroAttribute("failures=\"0\" tests=\"1\"", "failures"));
  }

  @Test
  void zeroAttributeReturnsFalseForNonZero() {
    assertFalse(SurefireReports.zeroAttribute("failures=\"3\"", "failures"));
  }

  @Test
  void failedClassNamesHandlesDirectoryWithIoExceptionChild(@TempDir Path tmp) throws IOException {
    // Create a non-regular file entry (a subdirectory with TEST- prefix)
    Files.createDirectories(tmp.resolve("TEST-subdir"));
    // Should not throw
    assertTrue(SurefireReports.reportedClassNames(tmp).isEmpty());
  }

  @Test
  void noFailuresProducesEmptySet(@TempDir Path tmp) throws IOException {
    writeReport(tmp, "TEST-com.example.A.xml", "com.example.A", 0, 0, 0);
    Set<String> failed = SurefireReports.failedClassNames(tmp);
    assertTrue(failed.isEmpty());
  }

  @Test
  void failuresAreCollectedByClassName(@TempDir Path tmp) throws IOException {
    writeReport(tmp, "TEST-com.example.A.xml", "com.example.A", 1, 0, 0);
    writeReport(tmp, "TEST-com.example.B.xml", "com.example.B", 0, 0, 0);
    writeReport(tmp, "TEST-com.example.C.xml", "com.example.C", 0, 1, 0);
    Set<String> failed = SurefireReports.failedClassNames(tmp);
    assertEquals(Set.of("com.example.A", "com.example.C"), failed);
  }

  @Test
  void skippedTestsAreNotCountedAsFailures(@TempDir Path tmp) throws IOException {
    writeReport(tmp, "TEST-com.example.S.xml", "com.example.S", 0, 0, 1);
    Set<String> failed = SurefireReports.failedClassNames(tmp);
    assertTrue(failed.isEmpty(), "skipped/disabled tests must not force rollback");
  }

  @Test
  void reportedNamesEnumerateEveryFile(@TempDir Path tmp) throws IOException {
    writeReport(tmp, "TEST-com.example.A.xml", "com.example.A", 0, 0, 0);
    writeReport(tmp, "TEST-com.example.B.xml", "com.example.B", 0, 0, 0);
    Set<String> reported = SurefireReports.reportedClassNames(tmp);
    assertEquals(Set.of("com.example.A", "com.example.B"), reported);
  }

  @Test
  void missingDirHasNoNames(@TempDir Path tmp) {
    Set<String> reported = SurefireReports.reportedClassNames(tmp.resolve("nope"));
    assertTrue(reported.isEmpty());
  }

  @Test
  void failedNameFallsBackToFileNameIfHeaderMissing(@TempDir Path tmp) throws IOException {
    Files.writeString(tmp.resolve("TEST-com.example.Bad.xml"), "<not-a-testsuite/>");
    Set<String> failed = SurefireReports.failedClassNames(tmp);
    assertTrue(failed.contains("com.example.Bad"));
  }

  @Test
  void zeroAttributeRecognisesNumbersWithWhitespace() {
    assertTrue(SurefireReports.zeroAttribute("name=\"x\" failures=\" 0 \"", "failures"));
    assertFalse(SurefireReports.zeroAttribute("name=\"x\" failures=\"3\"", "failures"));
  }

  private static void writeReport(Path dir, String name, String failures, String errors)
      throws IOException {
    String xml =
        "<?xml version=\"1.0\"?>\n"
            + "<testsuite name=\"x\" tests=\"1\" "
            + failures
            + " "
            + errors
            + ">\n</testsuite>\n";
    Files.writeString(dir.resolve(name), xml);
  }

  private static void writeReport(
      Path dir, String fileName, String suiteName, int failures, int errors, int skipped)
      throws IOException {
    String xml =
        "<?xml version=\"1.0\"?>\n"
            + "<testsuite name=\""
            + suiteName
            + "\" tests=\"5\""
            + " failures=\""
            + failures
            + "\""
            + " errors=\""
            + errors
            + "\""
            + " skipped=\""
            + skipped
            + "\">\n</testsuite>\n";
    Files.writeString(dir.resolve(fileName), xml);
  }

  private static String failures(int n) {
    return "failures=\"" + n + "\"";
  }

  private static String errors(int n) {
    return "errors=\"" + n + "\"";
  }

  private static Path createXmlFile(Path dir, String name, String content) throws IOException {
    Path f = dir.resolve(name);
    Files.writeString(f, content);
    return f;
  }
}
