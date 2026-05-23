# Failure modes and reliability
Every failure mode for `cull` degrades gracefully, and in the worst case it behaves exactly like a normal full test run.

Test failures fail your build as usual; a problem inside `cull` never does.

## Failure modes

| Situation                                            | Behaviour                                                                                                        |
|------------------------------------------------------|------------------------------------------------------------------------------------------------------------------|
| `forkCount=0` / `forkMode=never`                     | Run all tests, no cache commit (the agent needs a fork)                                                          |
| Cache state file fails its integrity check           | Rerun the whole module, cache commit still proceeds                                                              |
| I/O hooks cannot be installed on this JVM            | **Degraded mode:** any change under `src/**/resources` reruns the whole module, cache commit still proceeds      |
| Surefire `<disableXmlReport>true</disableXmlReport>` | Selection is still applied, but the cache commit is skipped for that module because pass/fail cannot be verified |
| Any other unexpected error                           | Rerun the whole module, no cache commit, the build fails as usual                                                |
