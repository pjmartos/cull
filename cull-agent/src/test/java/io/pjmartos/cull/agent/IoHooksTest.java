package io.pjmartos.cull.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.instrument.ClassDefinition;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/**
 * Verifies {@link IoHooks}: every probe target exists on the current JVM, and {@code install}
 * enforces all-or-nothing retransformation so partial failures degrade soundly.
 */
class IoHooksTest {

  @Test
  void probesAllExpectedTargetsOnThisJvm() {
    assertTrue(
        IoHooks.probeAllTargetsForCurrentJvm(),
        "every target FileInputStream/FileReader/Files.*/ClassLoader/Class method "
            + "must exist on the current JVM, otherwise we degrade for the entire module");
  }

  @Test
  void partialRetransformFailureDoesNotLeaveJvmPartiallyInstrumented() {
    // First target class retransforms; every subsequent one fails. The old
    // behavior returned true (success) on any single success, leaving some I/O
    // methods hooked and others not — silent under-attribution with no
    // degraded-mode warning. install() must instead report failure so premain
    // enters the sound degraded mode.
    FakeInstrumentation fake = new FakeInstrumentation(1);
    boolean installed = IoHooks.install(fake);
    assertFalse(installed, "partial retransform must be reported as failure");
    assertTrue(fake.removed, "the transformer must be backed out on partial failure");
  }

  @Test
  void allRetransformFailureReportsFailure() {
    FakeInstrumentation fake = new FakeInstrumentation(0);
    assertFalse(IoHooks.install(fake));
    assertTrue(fake.removed);
  }

  @Test
  void fullRetransformSuccessReportsSuccess() {
    FakeInstrumentation fake = new FakeInstrumentation(Integer.MAX_VALUE);
    assertTrue(IoHooks.install(fake), "every target retransformed; hooks are fully installed");
    assertFalse(fake.removed, "no back-out when all targets retransform");
  }

  private static final class FakeInstrumentation implements Instrumentation {
    private final int allowedSuccesses;
    private int retransformCalls;
    private final List<ClassFileTransformer> transformers = new ArrayList<>();
    volatile boolean removed;

    FakeInstrumentation(int allowedSuccesses) {
      this.allowedSuccesses = allowedSuccesses;
    }

    @Override
    public void addTransformer(ClassFileTransformer transformer, boolean canRetransform) {
      transformers.add(transformer);
    }

    @Override
    public void addTransformer(ClassFileTransformer transformer) {
      transformers.add(transformer);
    }

    @Override
    public boolean removeTransformer(ClassFileTransformer transformer) {
      removed = true;
      return transformers.remove(transformer);
    }

    @Override
    public boolean isRetransformClassesSupported() {
      return true;
    }

    @Override
    public void retransformClasses(Class<?>... classes) throws UnmodifiableClassException {
      if (retransformCalls++ >= allowedSuccesses) {
        throw new UnmodifiableClassException("simulated unmodifiable target");
      }
      // Successful no-op retransform: the transformer is not invoked, so no
      // rewrite occurs. This isolates the all-or-nothing accounting under test.
    }

    @Override
    public boolean isRedefineClassesSupported() {
      return false;
    }

    @Override
    public void redefineClasses(ClassDefinition... definitions) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isModifiableClass(Class<?> theClass) {
      return true;
    }

    @Override
    public Class<?>[] getAllLoadedClasses() {
      return new Class<?>[0];
    }

    @Override
    public Class<?>[] getInitiatedClasses(ClassLoader loader) {
      return new Class<?>[0];
    }

    @Override
    public long getObjectSize(Object objectToSize) {
      return 0L;
    }

    @Override
    public void appendToBootstrapClassLoaderSearch(JarFile jarfile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void appendToSystemClassLoaderSearch(JarFile jarfile) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isNativeMethodPrefixSupported() {
      return false;
    }

    @Override
    public void setNativeMethodPrefix(ClassFileTransformer transformer, String prefix) {
      throw new UnsupportedOperationException();
    }

    @Override
    public boolean isModifiableModule(Module module) {
      return false;
    }

    @Override
    public void redefineModule(
        Module module,
        java.util.Set<Module> extraReads,
        java.util.Map<String, java.util.Set<Module>> extraExports,
        java.util.Map<String, java.util.Set<Module>> extraOpens,
        java.util.Set<Class<?>> extraUses,
        java.util.Map<Class<?>, java.util.List<Class<?>>> extraProvides) {
      throw new UnsupportedOperationException();
    }
  }
}
