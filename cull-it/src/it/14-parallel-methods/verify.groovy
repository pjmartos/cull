File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"
assert !log.contains("VerifyError") : "no VerifyError; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed == 2 : "both runs must commit; saw ${committed}"

int counterRuns = (log =~ /Running io\.pjmartos\.cull\.it\.CounterMethodsTest/).count
assert counterRuns == 2 : "CounterMethodsTest depends on Counter and must re-run; saw ${counterRuns}"

return true
