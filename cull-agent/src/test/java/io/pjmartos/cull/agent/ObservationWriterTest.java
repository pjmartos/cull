package io.pjmartos.cull.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.core.Observations;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ObservationWriterTest {

  @Test
  void roundTripBasic(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("obs.bin");
    ObservationWriter w = new ObservationWriter(file);
    w.writeForkMetadata("fork-a");
    w.recordTestStart("com.example.A");
    w.recordClassLoad("com.example.A", "com/example/Helper");
    w.recordResourceRead("com.example.A", "/resource.txt");
    w.recordTestEnd("com.example.A");
    w.closeQuietly();

    Observations.Fork fork = Observations.readFile(file);
    assertEquals("fork-a", fork.forkId);
    Map<String, Set<String>> classDeps = Observations.mergeRuntimeClassDeps(List.of(fork));
    assertTrue(classDeps.get("com.example.A").contains("com/example/Helper"));
    Map<String, Set<String>> rsrc = Observations.mergeRuntimeResourceDeps(List.of(fork));
    assertTrue(rsrc.get("com.example.A").contains("/resource.txt"));
  }

  @Test
  void fsyncFlushesBufferedRecordsBeforeClose(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("obs.bin");
    ObservationWriter w = new ObservationWriter(file);
    w.writeForkMetadata("fork-sync");
    w.recordTestStart("com.example.A");
    w.recordClassLoad("com.example.A", "com/example/Helper");
    w.recordTestEnd("com.example.A");
    // Far fewer than the 256-record flush threshold and the writer is NOT
    // closed yet: only an explicit durable flush makes these bytes visible on
    // disk. A no-op fsync leaves them in the buffer and this read sees nothing.
    w.fsync();

    Observations.Fork fork = Observations.readFile(file);
    assertEquals("fork-sync", fork.forkId);
    Map<String, Set<String>> classDeps = Observations.mergeRuntimeClassDeps(List.of(fork));
    assertTrue(classDeps.get("com.example.A").contains("com/example/Helper"));
    w.closeQuietly();
  }

  @Test
  void discoveryAttributedToAllTestsInFork(@TempDir Path tmp) throws IOException {
    Path file = tmp.resolve("obs.bin");
    ObservationWriter w = new ObservationWriter(file);
    w.writeForkMetadata("fork-b");
    w.recordDiscoveryClass("org/springframework/Bootstrap");
    w.recordDiscoveryEnd();
    w.recordTestStart("com.example.A");
    w.recordTestEnd("com.example.A");
    w.recordTestStart("com.example.B");
    w.recordTestEnd("com.example.B");
    w.closeQuietly();

    Observations.Fork fork = Observations.readFile(file);
    Map<String, Set<String>> deps = Observations.mergeRuntimeClassDeps(List.of(fork));
    assertTrue(deps.get("com.example.A").contains("org/springframework/Bootstrap"));
    assertTrue(deps.get("com.example.B").contains("org/springframework/Bootstrap"));
  }
}
