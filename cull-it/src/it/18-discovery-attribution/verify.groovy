File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"
assert !log.contains("VerifyError") : "no VerifyError; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed == 2 : "both runs must commit; saw ${committed}"

assert log.contains("[cull] it-18-discovery-attribution: running all tests (wildcard)") :
    "first run is bootstrap; log:\n${log}"

// Per-fork discovery attribution: mutating Alpha must NOT pull BetaTest in.
// If discovery loads were globally attributed, BetaTest would also re-run
// because framework bootstrap classes would be associated with every test.
// The right answer is 1 (AlphaTest only).
def selectedLine = log =~ /\[cull\] it-18-discovery-attribution: selected (\d+) test\(s\)/
def matches = selectedLine.collect { it[1].toInteger() }
assert matches.size() >= 1 : "second run must report selection count; log:\n${log}"
assert matches[0] == 1 :
    "discovery attribution regressed: expected 1 test selected after Alpha change, got ${matches[0]}; log:\n${log}"

return true
