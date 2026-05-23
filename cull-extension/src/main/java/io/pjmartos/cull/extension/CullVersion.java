package io.pjmartos.cull.extension;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class CullVersion {

  private static final String RESOURCE = "/cull-build.properties";

  private static volatile String cached;

  private CullVersion() {}

  static String get() {
    String v = cached;
    if (v == null) {
      v = load();
      cached = v;
    }
    return v;
  }

  private static String load() {
    try (InputStream in = CullVersion.class.getResourceAsStream(RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException("cull could not locate " + RESOURCE + " on its classpath");
      }
      Properties props = new Properties();
      props.load(in);
      String v = props.getProperty("version");
      if (v == null || v.isEmpty() || v.startsWith("${")) {
        throw new IllegalStateException(
            "cull version was not filtered into " + RESOURCE + " (got '" + v + "')");
      }
      return v;
    } catch (IOException e) {
      throw new IllegalStateException("cull could not read " + RESOURCE, e);
    }
  }
}
