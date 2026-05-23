File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"
assert !log.contains("VerifyError") : "no VerifyError; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed == 2 : "both runs must commit; saw ${committed}"

// Under parallel=classes both test classes share a JVM fork. Each fork's
// discovery loads are attributed to every test in that fork, so a production-class
// change can pull in both tests (a sound over-approximation). After Counter is
// mutated, the impacted test must run; both running (wildcard-collapsed) is allowed.
int counterARuns = (log =~ /Running io\.pjmartos\.cull\.it\.CounterATest/).count
assert counterARuns == 2 : "CounterATest depends on Counter and must re-run; saw ${counterARuns}"

return true
