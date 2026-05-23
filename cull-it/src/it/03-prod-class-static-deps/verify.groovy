File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"
assert !log.contains("VerifyError") : "no VerifyError; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}"

assert log.contains("running all tests (wildcard)") :
    "first run is bootstrap; log:\n${log}"

int appleRuns = (log =~ /Running io\.pjmartos\.cull\.it\.AppleTest/).count
int pearRuns  = (log =~ /Running io\.pjmartos\.cull\.it\.PearTest/).count
int lemonRuns = (log =~ /Running io\.pjmartos\.cull\.it\.LemonTest/).count

assert appleRuns == 2 : "AppleTest must run twice (bootstrap + after Pear change); saw ${appleRuns}"
assert pearRuns == 2  : "PearTest must run twice (bootstrap + Pear change); saw ${pearRuns}"
assert lemonRuns == 1 : "LemonTest must NOT re-run after unrelated Pear change; saw ${lemonRuns}"

return true
