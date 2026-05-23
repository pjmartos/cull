package io.pjmartos.cull.extension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

final class CacheRetention {

  private CacheRetention() {}

  static void gc(Path cacheBase, int retention) {
    if (retention <= 0) return;
    try {
      List<Path> states = new ArrayList<>();
      try (Stream<Path> walk = Files.walk(cacheBase, 1)) {
        walk.filter(
                p -> {
                  Path fn = p.getFileName();
                  return fn != null && fn.toString().endsWith(".state.bin");
                })
            .forEach(states::add);
      }
      if (states.size() <= retention) return;
      states.sort(
          (a, b) -> {
            String la = readLastUsed(a);
            String lb = readLastUsed(b);
            return lb.compareTo(la);
          });
      for (int i = retention; i < states.size(); i++) {
        Path s = states.get(i);
        Path fnPath = s.getFileName();
        if (fnPath == null) continue;
        String fn = fnPath.toString();
        String base = fn.substring(0, fn.length() - ".state.bin".length());
        Files.deleteIfExists(s);
        Files.deleteIfExists(cacheBase.resolve(base + ".last_used"));
        // Retained-coverage baselines share the checksum stem; evict them in
        // lockstep so they are not orphaned past their owning state file.
        Files.deleteIfExists(cacheBase.resolve(base + ".jacoco.exec"));
        Files.deleteIfExists(cacheBase.resolve(base + ".jacoco-it.exec"));
      }
    } catch (IOException ignored) {
      // best effort
    }
  }

  static String readLastUsed(Path stateFile) {
    Path fnPath = stateFile.getFileName();
    if (fnPath == null) return "";
    String fn = fnPath.toString();
    Path companion =
        stateFile.resolveSibling(
            fn.substring(0, fn.length() - ".state.bin".length()) + ".last_used");
    try {
      if (Files.isRegularFile(companion)) {
        return new String(Files.readAllBytes(companion), StandardCharsets.UTF_8).trim();
      }
      return Files.getLastModifiedTime(stateFile).toString();
    } catch (IOException e) {
      return "";
    }
  }
}
