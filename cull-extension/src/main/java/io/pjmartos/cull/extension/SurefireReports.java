package io.pjmartos.cull.extension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

public final class SurefireReports {

  public enum Outcome {
    ALL_PASS,
    HAS_FAILURE,
    NO_REPORTS
  }

  private SurefireReports() {}

  public static Outcome scan(Path... reportDirs) {
    boolean any = false;
    for (Path dir : reportDirs) {
      if (!Files.isDirectory(dir)) continue;
      try (Stream<Path> walk = Files.walk(dir, 1)) {
        for (Path p : (Iterable<Path>) walk::iterator) {
          Path fnPath = p.getFileName();
          if (fnPath == null) continue;
          String fn = fnPath.toString();
          if (!fn.startsWith("TEST-") || !fn.endsWith(".xml")) continue;
          any = true;
          if (parseReportFailed(p)) return Outcome.HAS_FAILURE;
        }
      } catch (IOException e) {
        return Outcome.HAS_FAILURE;
      }
    }
    return any ? Outcome.ALL_PASS : Outcome.NO_REPORTS;
  }

  static boolean parseReportFailed(Path xml) {
    try {
      String content = Files.readString((xml), StandardCharsets.UTF_8);
      int idx = content.indexOf("<testsuite");
      if (idx < 0) return true;
      int end = content.indexOf('>', idx);
      if (end < 0) return true;
      String header = content.substring(idx, end);
      return !zeroAttribute(header, "failures") || !zeroAttribute(header, "errors");
    } catch (IOException e) {
      return true;
    }
  }

  public static Set<String> failedClassNames(Path... reportDirs) {
    Set<String> failed = new HashSet<>();
    for (Path dir : reportDirs) {
      if (!Files.isDirectory(dir)) continue;
      try (Stream<Path> walk = Files.walk(dir, 1)) {
        for (Path p : (Iterable<Path>) walk::iterator) {
          Path fnPath = p.getFileName();
          if (fnPath == null) continue;
          String fn = fnPath.toString();
          if (!fn.startsWith("TEST-") || !fn.endsWith(".xml")) continue;
          if (parseReportFailed(p)) {
            String cls = extractTestSuiteClassName(p);
            if (cls != null) {
              failed.add(cls);
            } else {
              String fromName = fn.substring("TEST-".length(), fn.length() - ".xml".length());
              if (!fromName.isEmpty()) failed.add(fromName);
            }
          }
        }
      } catch (IOException ignored) {
        // best effort: omit unreadable
      }
    }
    return failed;
  }

  public static Set<String> reportedClassNames(Path... reportDirs) {
    Set<String> seen = new HashSet<>();
    for (Path dir : reportDirs) {
      if (!Files.isDirectory(dir)) continue;
      try (Stream<Path> walk = Files.walk(dir, 1)) {
        for (Path p : (Iterable<Path>) walk::iterator) {
          Path fnPath = p.getFileName();
          if (fnPath == null) continue;
          String fn = fnPath.toString();
          if (!fn.startsWith("TEST-") || !fn.endsWith(".xml")) continue;
          String cls = extractTestSuiteClassName(p);
          if (cls != null) {
            seen.add(cls);
          } else {
            String fromName = fn.substring("TEST-".length(), fn.length() - ".xml".length());
            if (!fromName.isEmpty()) seen.add(fromName);
          }
        }
      } catch (IOException ignored) {
        // best effort
      }
    }
    return seen;
  }

  static String extractTestSuiteClassName(Path xml) {
    try {
      String content = Files.readString(xml, StandardCharsets.UTF_8);
      int idx = content.indexOf("<testsuite");
      if (idx < 0) return null;
      int end = content.indexOf('>', idx);
      if (end < 0) return null;
      String header = content.substring(idx, end);
      String marker = " name=\"";
      int i = header.indexOf(marker);
      if (i < 0) return null;
      i += marker.length();
      int j = header.indexOf('"', i);
      if (j < 0) return null;
      return header.substring(i, j);
    } catch (IOException e) {
      return null;
    }
  }

  static boolean zeroAttribute(String header, String name) {
    String marker = name + "=\"";
    int i = header.indexOf(marker);
    if (i < 0) return false;
    i += marker.length();
    int j = header.indexOf('"', i);
    if (j < 0) return false;
    String v = header.substring(i, j).trim();
    return "0".equals(v);
  }
}
