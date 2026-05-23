package io.pjmartos.cull.agent.listeners;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.pjmartos.cull.agent.ObservationWriter;
import io.pjmartos.cull.agent.RecorderHolder;
import io.pjmartos.cull.agent.TestContext;
import io.pjmartos.cull.core.Observations;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testng.IClass;
import org.testng.ITestContext;
import org.testng.ITestResult;

class CullTestngListenerTest {

  @AfterEach
  void clearHolder() {
    RecorderHolder.install(null, null);
  }

  @Test
  void attributesClassLoadsToRunningTestClass(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("obs.bin");
    ObservationWriter writer = new ObservationWriter(obs);
    writer.writeForkMetadata("fork-testng");
    RecorderHolder.install(new TestContext(), writer);

    CullTestngListener listener = new CullTestngListener();
    ITestResult result = testResult("com.example.FooTest");
    listener.onTestStart(result);
    RecorderHolder.recordClassLoad("com/example/Helper");
    listener.onTestSuccess(result);
    writer.closeQuietly();

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(obs)));
    assertTrue(deps.getOrDefault("com.example.FooTest", Set.of()).contains("com.example.Helper"));
  }

  @Test
  void nestedTestClassNormalizesToTopLevel(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("obs.bin");
    ObservationWriter writer = new ObservationWriter(obs);
    writer.writeForkMetadata("fork-nested");
    RecorderHolder.install(new TestContext(), writer);

    CullTestngListener listener = new CullTestngListener();
    ITestResult result = testResult("com.example.OuterTest$Nested");
    listener.onTestStart(result);
    RecorderHolder.recordClassLoad("com/example/Dep");
    listener.onTestFailure(result);
    writer.closeQuietly();

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(obs)));
    assertTrue(deps.containsKey("com.example.OuterTest"));
    assertFalse(deps.containsKey("com.example.OuterTest$Nested"));
  }

  @Test
  void skippedPathStillAttributesLoadsObservedDuringTheTest(@TempDir Path tmp) throws IOException {
    Path obs = tmp.resolve("obs.bin");
    ObservationWriter writer = new ObservationWriter(obs);
    writer.writeForkMetadata("fork-skip");
    RecorderHolder.install(new TestContext(), writer);

    CullTestngListener listener = new CullTestngListener();
    ITestResult a = testResult("com.example.ATest");
    listener.onTestStart(a);
    RecorderHolder.recordClassLoad("com/example/DuringA");
    listener.onTestSkipped(a);
    writer.closeQuietly();

    Map<String, Set<String>> deps =
        Observations.mergeRuntimeClassDeps(List.of(Observations.readFile(obs)));
    assertTrue(
        deps.getOrDefault("com.example.ATest", Set.of()).contains("com.example.DuringA"),
        "onTestStart/onTestSkipped must bracket attribution like the success path");
  }

  @Test
  void contextCallbacksAndNoAgentAreTolerated() {
    RecorderHolder.install(null, null);
    CullTestngListener listener = new CullTestngListener();
    ITestResult result = testResult("com.example.BazTest");
    ITestContext context = proxy(ITestContext.class, null, null);
    assertDoesNotThrow(
        () -> {
          listener.onStart(context);
          listener.onTestStart(result);
          listener.onTestFailedButWithinSuccessPercentage(result);
          listener.onTestSuccess(result);
          listener.onFinish(context);
        });
  }

  private static ITestResult testResult(String className) {
    IClass iclass = proxy(IClass.class, "getName", className);
    return proxy(ITestResult.class, "getTestClass", iclass);
  }

  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> iface, String method, Object value) {
    return (T)
        Proxy.newProxyInstance(
            CullTestngListenerTest.class.getClassLoader(),
            new Class<?>[] {iface},
            (p, m, args) -> {
              if (method != null && method.equals(m.getName())) {
                return value;
              }
              switch (m.getName()) {
                case "toString":
                  return iface.getSimpleName() + "-stub";
                case "hashCode":
                  return System.identityHashCode(p);
                case "equals":
                  return p == args[0];
                default:
                  return defaultValue(m.getReturnType());
              }
            });
  }

  private static Object defaultValue(Class<?> t) {
    if (!t.isPrimitive() || t == void.class) {
      return null;
    }
    if (t == boolean.class) {
      return Boolean.FALSE;
    }
    if (t == long.class) {
      return 0L;
    }
    if (t == float.class) {
      return 0.0f;
    }
    if (t == double.class) {
      return 0.0d;
    }
    if (t == char.class) {
      return '\0';
    }
    return 0;
  }
}
