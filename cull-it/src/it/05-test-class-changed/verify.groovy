File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}"

int alphaRuns = (log =~ /Running io\.pjmartos\.cull\.it\.AlphaTest/).count
int betaRuns  = (log =~ /Running io\.pjmartos\.cull\.it\.BetaTest/).count

assert alphaRuns == 2 : "AlphaTest re-runs after self-mutation; saw ${alphaRuns}"
assert betaRuns == 1  : "BetaTest must NOT re-run after only AlphaTest changes; saw ${betaRuns}"

return true
