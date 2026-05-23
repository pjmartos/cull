File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}"

int doorRuns       = (log =~ /Running io\.pjmartos\.cull\.it\.DoorTest/).count
int standaloneRuns = (log =~ /Running io\.pjmartos\.cull\.it\.StandaloneTest/).count

assert doorRuns == 2       : "DoorTest re-runs because base class changed; saw ${doorRuns}"
assert standaloneRuns == 1 : "StandaloneTest must NOT re-run; saw ${standaloneRuns}"

return true
