# Configuration reference
All settings are read from user and system properties; you can put them in the POM or pass them on the command line, for example `-Dcull.disabled=true`.

If you use the plugin directly rather than the core extension, every property below also works as a plugin `<configuration>` element.

The [README](../README.md) covers the few properties most people need. This page lists them all.

## Properties

| Property                | Default                                           | Meaning                                                                                                                                                                                                         |
|-------------------------|---------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `cull.disabled`         | `false`                                           | Turn `cull` off completely, but leave the cache alone.                                                                                                                                                          |
| `cull.fallback.runAll`  | `false`                                           | Run all tests this time, but still record results and update the cache. Useful for e.g. a nightly build that should keep the cache warm.                                                                        |
| `cull.crossmodule`      | `full`                                            | Reactor strategy. `full` reruns only the affected tests in depending sub-modules; `off` runs everything in depending sub-modules as soon as one production class and/or resource changes in an upstream module. |
| `cull.cache.directory`  | `${user.home}/.cull/cache`                        | Where the per-module cache is stored.                                                                                                                                                                           |
| `cull.cache.retention`  | `5`                                               | How many of the most recent cache snapshots to keep per module. Older ones are removed when the cache is committed.                                                                                             |
| `cull.test.includes`    | Surefire `<includes>`, otherwise a name heuristic | Comma-separated class-name patterns that decide which unit tests count as candidates. Set this if your test classes do not follow the usual naming and `cull` does not pick them up.                            |
| `cull.it.includes`      | Failsafe `<includes>`, otherwise a name heuristic | The same, for integration tests.                                                                                                                                                                                |
| `cull.iohooks.disabled` | `false`                                           | Accept reduced resource tracking on this JVM and hide the one-time warning about it. With reduced tracking, any change under `src/**/resources` reruns the whole module.                                        |

## `.cullignore`
The project checksum normally changes whenever your dependencies or plugins' configuration changes.

Some plugins inject values that change on every build (e.g. timestamps, build numbers, absolute paths) and these would lead to a different checksum for no real reason, costing you a full run each time.

An optional `.cullignore` file at the project root removes such values from the checksum. The rules:
* One pattern per line, in the form `groupId:artifactId#dotted.path`.
* `groupId` and `artifactId` may each be `*` to match any.
* A path element may be `*` (one level) or `**` (any nesting).
* Blank lines and lines starting with `#` are ignored.

```
# Ignore the build timestamp injected by the git-commit-id plugin
io.github.git-commit-id:git-commit-id-maven-plugin#git.build.time
```
