package io.pjmartos.cull.agent.listeners;

final class TestClassNames {

  private TestClassNames() {}

  static String topLevel(String name) {
    if (name == null) return null;
    int idx = name.indexOf('$');
    return idx < 0 ? name : name.substring(0, idx);
  }
}
