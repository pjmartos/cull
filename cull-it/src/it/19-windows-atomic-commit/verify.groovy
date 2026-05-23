File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 1 : "atomic single-file commit must succeed; saw ${committed}"
assert !log.contains("AtomicMoveNotSupported") : "atomic move must work on this filesystem; log:\n${log}"

File cacheDir = new File(basedir, ".cull-cache/io/pjmartos/cull/it/it-19-windows-atomic-commit")
assert cacheDir.isDirectory() : "cache directory must exist after commit"
File[] stateFiles = cacheDir.listFiles({ d, n -> n.endsWith(".state.bin") } as FilenameFilter)
assert stateFiles != null && stateFiles.length >= 1 :
    "exactly one .state.bin must be present after a successful single-file ATOMIC_MOVE"

return true
