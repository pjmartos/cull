package io.pjmartos.cull.core;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

public final class Observations {

  public static final int TAG_CLASS_LOAD = 0;
  public static final int TAG_RESOURCE_READ = 1;
  public static final int TAG_ORPHAN_CLASS = 2;
  public static final int TAG_ORPHAN_RESOURCE = 3;
  public static final int TAG_DISCOVERY_CLASS = 4;
  public static final int TAG_TEST_START = 5;
  public static final int TAG_TEST_END = 6;
  public static final int TAG_FORK_METADATA = 7;
  public static final int TAG_DISCOVERY_END = 8;
  public static final int TAG_CLASS_START = 9;
  public static final int TAG_CLASS_END = 10;

  public static final class Record {
    public final int tag;
    public final String testId;
    public final String payload;

    public Record(int tag, String testId, String payload) {
      this.tag = tag;
      this.testId = testId;
      this.payload = payload;
    }
  }

  public static final class Fork {
    public final String forkId;
    public final List<Record> records;

    public Fork(String forkId, List<Record> records) {
      this.forkId = forkId;
      this.records = records;
    }
  }

  private Observations() {}

  public static List<Fork> readDirectory(Path dir) throws IOException {
    if (!Files.isDirectory(dir)) {
      return Collections.emptyList();
    }
    List<Fork> out = new ArrayList<>();
    try (Stream<Path> walk = Files.walk(dir, 1)) {
      walk.filter(
              p -> {
                Path fn = p.getFileName();
                if (fn == null) return false;
                String name = fn.toString();
                return name.startsWith("observations-") && name.endsWith(".bin");
              })
          .forEach(
              p -> {
                try {
                  out.add(readFile(p));
                } catch (IOException ignored) {
                  // skip corrupt/partial files
                }
              });
    }
    return out;
  }

  public static Fork readFile(Path file) throws IOException {
    try (InputStream raw = Files.newInputStream(file);
        InputStream in = new BufferedInputStream(raw)) {
      String forkId = "unknown";
      List<Record> records = new ArrayList<>();
      boolean progressed = false;
      try {
        int firstTag = in.read();
        if (firstTag < 0) {
          return new Fork(forkId, records);
        }
        if (firstTag == TAG_FORK_METADATA) {
          int idLen = Varint.readUnsignedInt(in);
          byte[] idBytes = readN(in, idLen);
          forkId = new String(idBytes, StandardCharsets.UTF_8);
          int tsLen = Varint.readUnsignedInt(in);
          readN(in, tsLen);
        } else {
          records.add(parseRecord(firstTag, in));
        }
        progressed = true;
        int tag;
        while ((tag = in.read()) >= 0) {
          records.add(parseRecord(tag, in));
        }
      } catch (IOException truncatedTail) {
        // A fork JVM that crashed mid-write leaves a partially written trailing
        // record. The format is append-only and flushed periodically, so every
        // record before the torn tail is intact: keep them and drop only the
        // incomplete remainder instead of discarding the whole fork's data
        // (which would silently under-attribute dependencies for those tests).
        // A file with nothing salvageable is genuinely corrupt and is reported
        // so the caller (readDirectory) skips it.
        if (!progressed && records.isEmpty()) {
          throw truncatedTail;
        }
      }
      return new Fork(forkId, records);
    }
  }

  private static Record parseRecord(int tag, InputStream in) throws IOException {
    String testId = null;
    if (hasTestId(tag)) {
      int idLen = Varint.readUnsignedInt(in);
      byte[] idBytes = readN(in, idLen);
      testId = new String(idBytes, StandardCharsets.UTF_8);
    }
    int pLen = Varint.readUnsignedInt(in);
    byte[] pBytes = readN(in, pLen);
    return new Record(tag, testId, new String(pBytes, StandardCharsets.UTF_8));
  }

  private static boolean hasTestId(int tag) {
    return tag == TAG_CLASS_LOAD
        || tag == TAG_RESOURCE_READ
        || tag == TAG_TEST_START
        || tag == TAG_TEST_END
        || tag == TAG_CLASS_START
        || tag == TAG_CLASS_END;
  }

  static final int MAX_OBSERVATION_FIELD_BYTES = 64 * 1024 * 1024;

  private static byte[] readN(InputStream in, int n) throws IOException {
    if (n < 0 || n > MAX_OBSERVATION_FIELD_BYTES) {
      throw new IOException(
          "Corrupt observation file: invalid field length "
              + n
              + " (max "
              + MAX_OBSERVATION_FIELD_BYTES
              + ")");
    }
    byte[] out = new byte[n];
    int off = 0;
    while (off < n) {
      int r = in.read(out, off, n - off);
      if (r < 0) {
        throw new IOException("EOF reading " + n);
      }
      off += r;
    }
    return out;
  }

  public static Map<String, Set<String>> mergeRuntimeClassDeps(List<Fork> forks) {
    return mergeRuntimeClassDeps(forks, false);
  }

  /**
   * Merge per-fork runtime class loads into a test→deps map.
   *
   * <p>A fork that emitted class windows ({@code TAG_CLASS_START}) and is not forced into legacy
   * mode is attributed precisely: windowed loads stay with their class, JUnit-discovery-phase loads
   * are dropped (a launcher artifact; the same classes reload inside the owning window and are also
   * covered by the static closure), and orphan loads between windows are attributed to the next
   * window opened (trailing orphans to the last). A fork without class windows — or with {@code
   * legacyFanout} — keeps the original sound behavior: discovery + orphan loads fanned to every
   * test in the fork.
   */
  public static Map<String, Set<String>> mergeRuntimeClassDeps(
      List<Fork> forks, boolean legacyFanout) {
    Map<String, Set<String>> result = new HashMap<>();
    for (Fork f : forks) {
      Set<String> testsInThisFork = new HashSet<>();
      List<String> discoveryClasses = new ArrayList<>();
      List<String> orphanClasses = new ArrayList<>();
      List<String> pendingOrphans = new ArrayList<>();
      boolean discoveryEnded = false;
      boolean classWindows = false;
      String lastWindowId = null;
      for (Record r : f.records) {
        switch (r.tag) {
          case TAG_CLASS_START:
          case TAG_TEST_START:
            // A class window opens exactly like a test window; only the gate
            // (was a class window seen in this fork?) differs.
            if (r.tag == TAG_CLASS_START) {
              classWindows = true;
            }
            testsInThisFork.add(r.testId);
            result.computeIfAbsent(r.testId, k -> new HashSet<>());
            discoveryEnded = true;
            if (!pendingOrphans.isEmpty()) {
              result.get(r.testId).addAll(pendingOrphans);
              pendingOrphans.clear();
            }
            lastWindowId = r.testId;
            break;
          case TAG_CLASS_LOAD:
            result.computeIfAbsent(r.testId, k -> new HashSet<>()).add(r.payload);
            break;
          case TAG_DISCOVERY_CLASS:
            discoveryClasses.add(r.payload);
            break;
          case TAG_DISCOVERY_END:
            discoveryEnded = true;
            break;
          case TAG_ORPHAN_CLASS:
            if (discoveryEnded) {
              orphanClasses.add(r.payload);
              pendingOrphans.add(r.payload);
            } else {
              discoveryClasses.add(r.payload);
            }
            break;
          default:
            break;
        }
      }
      if (legacyFanout || !classWindows) {
        for (String t : testsInThisFork) {
          Set<String> deps = result.computeIfAbsent(t, k -> new HashSet<>());
          deps.addAll(discoveryClasses);
          deps.addAll(orphanClasses);
        }
      } else if (!pendingOrphans.isEmpty() && lastWindowId != null) {
        result.get(lastWindowId).addAll(pendingOrphans);
      }
    }
    return result;
  }

  public static Map<String, Set<String>> mergeRuntimeResourceDeps(List<Fork> forks) {
    return mergeRuntimeResourceDeps(forks, false);
  }

  /**
   * Merge per-fork runtime resource reads into a test→deps map. Same gate as {@link
   * #mergeRuntimeClassDeps(List, boolean)}: a fork with class windows (and not forced legacy) keeps
   * windowed reads with their class, drops pre-first-window reads (a launcher/bootstrap artifact),
   * and routes between-window orphan reads to the next window opened (trailing to the last).
   * Without class windows — or with {@code legacyFanout} — a read while no test is active is
   * attributed to every test in the fork (the original sound bound).
   */
  public static Map<String, Set<String>> mergeRuntimeResourceDeps(
      List<Fork> forks, boolean legacyFanout) {
    Map<String, Set<String>> result = new HashMap<>();
    for (Fork f : forks) {
      Set<String> testsInThisFork = new HashSet<>();
      List<String> orphanResources = new ArrayList<>();
      List<String> pendingOrphans = new ArrayList<>();
      boolean discoveryEnded = false;
      boolean classWindows = false;
      String lastWindowId = null;
      for (Record r : f.records) {
        switch (r.tag) {
          case TAG_CLASS_START:
          case TAG_TEST_START:
            if (r.tag == TAG_CLASS_START) {
              classWindows = true;
            }
            testsInThisFork.add(r.testId);
            result.computeIfAbsent(r.testId, k -> new HashSet<>());
            discoveryEnded = true;
            if (!pendingOrphans.isEmpty()) {
              result.get(r.testId).addAll(pendingOrphans);
              pendingOrphans.clear();
            }
            lastWindowId = r.testId;
            break;
          case TAG_RESOURCE_READ:
            result.computeIfAbsent(r.testId, k -> new HashSet<>()).add(r.payload);
            break;
          case TAG_DISCOVERY_END:
            discoveryEnded = true;
            break;
          case TAG_ORPHAN_RESOURCE:
            orphanResources.add(r.payload);
            if (discoveryEnded) {
              pendingOrphans.add(r.payload);
            }
            break;
          default:
            break;
        }
      }
      // Without class windows (old listener / unsupported engine) or under the
      // legacy escape hatch, a read while no test is active cannot be pinned to
      // one test, so it is attributed to every test in the fork — the original
      // tightest sound bound. With class windows, reads are precisely scoped;
      // only between-window orphans survive, routed by the same O2 rule.
      if (legacyFanout || !classWindows) {
        for (String t : testsInThisFork) {
          result.computeIfAbsent(t, k -> new HashSet<>()).addAll(orphanResources);
        }
      } else if (!pendingOrphans.isEmpty() && lastWindowId != null) {
        result.get(lastWindowId).addAll(pendingOrphans);
      }
    }
    return result;
  }
}
