# Coverage retention
`cull` keeps your JaCoCo coverage report stable even when it runs only a subset of your tests. This is on automatically whenever the `jacoco-maven-plugin` is configured.

## The problem
A coverage tool only sees the tests that actually ran. When `cull` selects a small subset, a plain `jacoco.exec` reflects just that subset, and the coverage report drops sharply, sometimes towards zero. That number is misleading: the code is still covered, it just was not re-tested this time.

## The solution
When the JaCoCo plugin is present, `cull` treats each module's coverage `.exec` file as a cached baseline, keyed by the same project checksum as the test-to-code graph. On each build it:
1. Restores the cached baseline into JaCoCo's destination file (`${project.build.directory}/jacoco.exec` or `${project.build.directory}/jacoco-it.exec`) before any tests run.
2. Instructs Surefire / Failsafe to execute the selected tests
3. Merges their coverage results with JaCoCo's restored file
4. Persists the combined result during cache commit after a successful build

JaCoCo drops, at report time, any entry whose class id no longer matches the recompiled bytecode; a stale baseline never reports coverage for code that has since changed.

When `cull` selects zero tests, the report still reflects the full suite.

## Scope and limits
* Both unit and integration coverage are retained: `jacoco.exec` through Surefire and `jacoco-it.exec` through Failsafe, mirroring `cull:select` and `cull:select-it`.
* **Append mode is required.** Retention relies on JaCoCo's default append mode. If JaCoCo is configured to overwrite (`append=false`), coverage tracks the executed subset as it would without `cull`. This avoids clobbering or shrinking the baseline.
* On each cache commit, `cull` merges the accumulated execution data into a single block.
* The baseline lives beside the graph under `~/.cull/cache` and is evicted by the same `cull.cache.retention` policy. A dependency or plugin change leads to a new checksum, and the next full run rebuilds a fresh baseline.
