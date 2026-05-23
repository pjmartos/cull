# How it works
This document explains what `cull` does during a build: where it binds into the Maven lifecycle, how it decides which tests to run, and how it builds and stores its test-to-code graph.

## Lifecycle binding
The core extension injects test listeners for Surefire and Failsafe, and binds two goals:
* `cull:select` at the `process-test-classes` phase (unit tests, via Surefire).
* `cull:select-it` at the `pre-integration-test` phase (integration tests, via Failsafe).

If you cannot use a core extension, you can declare the `io.github.pjmartos.cull:cull-maven-plugin` plugin yourself and bind these two goals.

## The project checksum
Before it can reuse anything, `cull` works out a project checksum for each module. The checksum covers the things that, when they change, make the old graph unsafe to trust, i.e. dependencies and plugins' configuration.

You can stop volatile values (build timestamps, build numbers, absolute paths) from inadvertently altering the checksum with a `.cullignore` file. See the [Configuration reference](configuration.md#cullignore) for the format.

## Selecting tests
At selection time `cull` locates the module-specific folder inside the root cache directory (by default, `${user.home}/.cull/cache`), then looks for a "state file" contained therein whose file name matches the current project checksum (henceforth, the "cache entry"):
* If a matching entry exists, `cull` hashes the current source, test and resource files and compares them with the hashes stored in the cache entry. It then selects every test that exercises one or more changed files, plus any new test classes and any tests that failed on the previous run.
* If no matching entry exists, `cull` runs all tests, then builds and stores a fresh cache entry.

Selecting zero tests is a normal outcome: when nothing relevant has changed, there is nothing to run.

## Building the graph
`cull` combines two sources of information so that the graph is accurate:
* Runtime observations: A Java agent records what each test actually loads while it runs. This captures links that static analysis would miss, such as reflection, `ServiceLoader` and dynamic dispatch.
* Static class-file analysis: On top of the runtime data, `cull` reads the compiled class files and adds references that are declared but not loaded on a given run. This keeps the next run correct even if a code path was not exercised this time.

## Committing the cache
The graph is rebuilt and committed atomically at the end of the build, and only if every selected test passed.

If the build fails for whatever reason, `cull` rolls back and leaves the existing cache untouched, as if it was not installed.

## Multi-module reactors
`cull` is reactor-aware and selects tests across modules at a fine grain. The behaviour is controlled by the `cull.crossmodule` property:
* `full`: A change in an upstream module reruns that module's affected tests and the tests in depending modules that exercise the changed classes.
* `off`: `cull` runs everything in depending sub-modules as soon as one production class and/or resource changes in an upstream module. When `cull` is uncertain, it falls back to this mode.
