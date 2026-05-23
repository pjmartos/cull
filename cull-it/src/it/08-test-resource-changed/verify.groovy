File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}"

int dataRuns  = (log =~ /Running io\.pjmartos\.cull\.it\.DataReaderTest/).count
int otherRuns = (log =~ /Running io\.pjmartos\.cull\.it\.OtherTest/).count

assert dataRuns >= 2  : "DataReaderTest re-runs after data.txt change; saw ${dataRuns}"
// degraded mode (no I/O hooks) re-runs everything in module on resource change;
// that is sound but coarse. Either way, DataReaderTest must rerun.
assert otherRuns >= 1 : "OtherTest must run at least once; saw ${otherRuns}"

return true
