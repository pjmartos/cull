# cull

[![CI](https://github.com/pjmartos/cull/actions/workflows/ci.yml/badge.svg?branch=main&style=plastic)](https://github.com/pjmartos/cull/actions/workflows/ci.yml)
[![maven-central](https://img.shields.io/maven-central/v/io.github.pjmartos.cull/cull-extension?style=plastic)](https://central.sonatype.com/artifact/io.github.pjmartos.cull/cull-extension)
[![License](https://img.shields.io/github/license/pjmartos/cull?style=plastic)](LICENSE)

`cull` is a Test Impact Analysis tool for Maven. Instead of running your whole suite on every build, it works out which tests a change can actually affect and runs only those. When it is not sure, it runs everything.

`cull` never fails your build on its own. Test failures fail the build as usual, but any problem inside `cull` simply falls back to "run all tests and leave the cache alone".

It plugs into the standard Maven lifecycle, builds a graph that links each test to the code it depends on, and asks Surefire and Failsafe to run just the affected tests. The graph is cached per module under `~/.cull/cache`, so the analysis cost is paid once and reused on later builds.

## Features
* No runtime dependencies
* Works with JUnit 5, JUnit 4 and TestNG
* Hybrid graph
  * A Java agent records what each test loads at run time
  * Static class-file analysis adds potentially missing references that are declared but not loaded
* Multi-module aware, with fine-grained selection across reactor modules
* Keeps JaCoCo coverage stable across partial runs

For how selection works under the hood, see [How it works](docs/how-it-works.md).

For how JaCoCo coverage is retained across partial builds, see [Coverage retention](docs/coverage-retention.md).

## Requirements
| Component           | Minimum | Tested up to |
|---------------------|---------|--------------|
| Java                | 11      | 25           |
| Maven               | 3.6.3   | 3.9.x        |
| Surefire / Failsafe | 3.0     | 3.x          |

`cull` is tested against JUnit 5.10.x, JUnit 4.13.x and TestNG 7.x, but imposes no minimum of its own.

JaCoCo and other `-javaagent:` tools run alongside `cull` without conflict.

## Installation
Register the core extension in `.mvn/extensions.xml` at the root of your repository:

```xml
<extensions>
  <extension>
    <groupId>io.github.pjmartos.cull</groupId>
    <artifactId>cull-extension</artifactId>
    <version>${cull.version}</version>       <!-- Latest version at the time of this writing: 0.0.3 -->
  </extension>
</extensions>
```

If core extensions are not allowed in your environment, you can declare the `io.github.pjmartos.cull:cull-maven-plugin` plugin directly in your POM.

```xml
<plugins>
  <plugin>
    <groupId>io.github.pjmartos.cull</groupId>
    <artifactId>cull-maven-plugin</artifactId>
    <version>${cull.version}</version>       <!-- Latest version at the time of this writing: 0.0.3 -->
    <executions>
      <execution>
        <id>default-select</id>
        <goals>
          <goal>select</goal>
        </goals>   
      </execution>
      <execution>
        <id>default-select-it</id>
        <goals>
          <goal>select-it</goal>
        </goals>   
      </execution>
    </executions>
  </plugin>
</plugins>
```

See [How it works](docs/how-it-works.md) for the goals and phases `cull` binds to.

## Running cull and selecting tests
`cull` runs as part of a normal `mvn test` or `mvn verify`. You can tell it is working from the build log:
* Each module logs one line, for example `[INFO] [cull] app: selected 2 test(s) in 91 ms`, and at the end of the session `cull` prints `[cull] committed cache for app checksum=...` (or `[cull] rolled back ...`)
* On a warm build with no relevant change, `cull` selects 0 tests and Surefire runs nothing (expected)
* Force a full run without losing the cache with `-Dcull.fallback.runAll=true` (handy for nightly builds), or switch it off completely with `-Dcull.disabled=true`
* Integration-test selection is experimental and off by default: every integration test runs each build while only unit tests are culled. Turn it on with `-Dcull.experimental.itSelection.enabled=true`
* Reset the cache by deleting `~/.cull/cache` (or just the module's subfolder within that folder)
* A damaged cache repairs itself: a failed integrity check is treated as "no cache"

Example: a warm single-module build where two source files changed. Consequently, only the tests that exercise those classes are selected (the exact wording, timings and checksums vary):

```
[INFO] --- cull:select (cull-select) @ app ---
[INFO] [cull] app: selected 2 test(s) in 91 ms
[INFO] --- maven-surefire-plugin:test (default-test) @ app ---
[INFO] Running com.example.OrderServiceTest
[INFO] Running com.example.OrderMapperTest
[INFO] Tests run: 7, Failures: 0, Errors: 0, Skipped: 0
[INFO] BUILD SUCCESS
[cull] committed cache for app checksum=8c41d0f2ab93e7150d6a
```

When nothing relevant changed, the line reads `[cull] app: selected 0 test(s)` and Surefire runs no tests. In a reactor, an upstream change reruns that module's affected tests plus the tests in depending sub-modules that exercise the changed classes.

## Configuration
All settings are read from user and system properties, so they work in the POM or on the command line (`-Dcull.disabled=true`).

The most useful ones:

| Property                                | Default                    | Meaning                                                                                                                                                                                                         |
|-----------------------------------------|----------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `cull.disabled`                         | `false`                    | Turn `cull` off, but leave the cache alone.                                                                                                                                                                     |
| `cull.fallback.runAll`                  | `false`                    | Run all tests this time, but still record results and update the cache.                                                                                                                                         |
| `cull.crossmodule`                      | `full`                     | Reactor strategy. `full` reruns only the affected tests in depending sub-modules; `off` runs everything in depending sub-modules as soon as one production class and/or resource changes in an upstream module. |
| `cull.experimental.itSelection.enabled` | `false`                    | Experimental. When `true`, `cull` inspects the changed files and selects integration tests accordingly.                                                                                                         |
| `cull.cache.directory`                  | `${user.home}/.cull/cache` | Where the per-module cache is stored.                                                                                                                                                                           |

For the full list of properties and the `.cullignore` file, see the [Configuration reference](docs/configuration.md).

Every configuration property also works as a `<configuration>` element for `cull-maven-plugin`.

## Limitations
* `cull` assumes a case-sensitive filesystem. A rename that changes only letter case may not be picked up on a case-insensitive filesystem like NTFS.

For a complete list of edge cases, see [Failure modes and reliability](docs/failure-modes.md).
