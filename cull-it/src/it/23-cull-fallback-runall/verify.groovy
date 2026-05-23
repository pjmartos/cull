File buildLog = new File(basedir, "build.log")
assert buildLog.exists()
String log = buildLog.text

// Two invocations should both commit cache. The first is a normal bootstrap
// (wildcard because no prior graph); the second uses cull.fallback.runAll=true
// which forces every test to run while still committing.
int committed = (log =~ /\[cull\] committed cache/).count
assert committed == 2 :
    "both runs must commit cache (bootstrap + fallback-runAll); saw ${committed}; log:\n${log}"

// Each invocation runs the same 2 tests. Surefire prints "Tests run: 2" twice.
int testsRanTwo = (log =~ /Tests run: 2, Failures: 0, Errors: 0, Skipped: 0/).count
assert testsRanTwo == 2 :
    "fallback.runAll must run every test (2 tests x 2 invocations expected); saw ${testsRanTwo}; log:\n${log}"

// Both invocations should report wildcard. The bootstrap is naturally wildcard;
// the second invocation would normally select 0 tests because nothing changed,
// but fallback.runAll forces wildcard.
int wildcards = (log =~ /\[cull\] it-23-cull-fallback-runall: running all tests \(/).count
assert wildcards == 2 :
    "both bootstrap and fallback.runAll runs must report wildcard; saw ${wildcards}; log:\n${log}"

// And no run must report a small selected count (otherwise fallback.runAll was ignored).
int selectedZero =
    (log =~ /\[cull\] it-23-cull-fallback-runall: selected 0 test\(s\)/).count
assert selectedZero == 0 :
    "fallback.runAll must override the 'no changes' selection; saw ${selectedZero}; log:\n${log}"

return true
