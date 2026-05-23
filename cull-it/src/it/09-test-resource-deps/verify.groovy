File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}"

int alphaRuns = (log =~ /Running io\.pjmartos\.cull\.it\.AlphaTest/).count
int betaRuns  = (log =~ /Running io\.pjmartos\.cull\.it\.BetaTest/).count
int isolatedRuns = (log =~ /Running io\.pjmartos\.cull\.it\.IsolatedTest/).count

assert alphaRuns >= 2 : "AlphaTest reads shared.txt and must re-run; saw ${alphaRuns}"
assert betaRuns  >= 2 : "BetaTest reads shared.txt and must re-run; saw ${betaRuns}"
assert isolatedRuns == 1 :
    "IsolatedTest depends only on isolated.txt and must NOT re-run; saw ${isolatedRuns}; log:\n${log}"

return true
