package io.pjmartos.cull.agent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;

public final class RecordingTransformer implements ClassFileTransformer {

  // null returns signal "no transformation" per Instrumentation API contract.
  @Override
  @SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
  public byte[] transform(
      ClassLoader loader,
      String name,
      Class<?> classBeingRedefined,
      ProtectionDomain protectionDomain,
      byte[] classfileBuffer) {
    if (name == null) {
      return null;
    }
    if (isCullInternal(name)) {
      return null;
    }
    RecorderHolder.recordClassLoad(name);
    return null;
  }

  private static boolean isCullInternal(String name) {
    return name.startsWith("io/pjmartos/cull/agent/")
        || name.startsWith("io/pjmartos/cull/core/")
        || name.startsWith("io/pjmartos/cull/agent/shaded/");
  }
}
