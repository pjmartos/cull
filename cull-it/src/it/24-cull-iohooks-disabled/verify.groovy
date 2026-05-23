File buildLog = new File(basedir, "build.log")
assert buildLog.exists()
String log = buildLog.text

// Explicit opt-in to degraded mode must suppress the involuntary warning
// that the agent prints when the probe fails on an unsupported JDK.
assert !log.contains("[cull] WARNING: I/O hooks unavailable") :
    "explicit opt-in must suppress the I/O-hooks warning; log:\n${log}"

assert !log.contains("agent disabled:") :
    "agent must still load (just without I/O hooks); log:\n${log}"

// Both runs must commit (no failure, no rollback).
int committed = (log =~ /\[cull\] committed cache/).count
assert committed == 2 :
    "both invocations must commit cache; saw ${committed}; log:\n${log}"

// Tests must run normally in the first invocation (bootstrap).
int testsRan = (log =~ /Tests run: 1, Failures: 0, Errors: 0, Skipped: 0/).count
assert testsRan >= 1 :
    "FooTest must run at least once (bootstrap); saw ${testsRan}; log:\n${log}"

// Second invocation has no changes, so selection must report 0 selected.
assert log.contains("[cull] it-24-cull-iohooks-disabled: selected 0 test(s)") :
    "second run with no changes must select 0 tests; log:\n${log}"

// Cache state.bin must exist for this checksum.
File cacheRoot = new File(basedir, ".cull-cache")
assert cacheRoot.exists() : "cache root must be populated"
def stateFiles = []
cacheRoot.eachFileRecurse {
    if (it.name.endsWith(".state.bin")) stateFiles << it
}
assert !stateFiles.isEmpty() : "expected .state.bin under ${cacheRoot}"

return true
