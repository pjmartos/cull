File buildLog = new File(basedir, "build.log")
assert buildLog.exists()
String log = buildLog.text

// When cull.disabled=true the extension must not inject the agent, must not
// publish a selection, and must not commit anything.
assert log.contains("Tests run: 1, Failures: 0, Errors: 0, Skipped: 0") :
    "the single FooTest must still run when cull is disabled; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed == 0 :
    "no commit must happen when cull.disabled=true; saw ${committed}; log:\n${log}"

int selectedLines = (log =~ /\[cull\] it-22-cull-disabled: /).count
assert selectedLines == 0 :
    "no cull selection log lines must appear when disabled; saw ${selectedLines}; log:\n${log}"

assert !log.contains("-javaagent:") || !log.contains("cull-agent") :
    "the cull agent must not be attached when cull.disabled=true; log:\n${log}"

File cacheRoot = new File(basedir, ".cull-cache")
if (cacheRoot.exists()) {
    def stateFiles = []
    cacheRoot.eachFileRecurse { if (it.name.endsWith(".state.bin")) stateFiles << it }
    assert stateFiles.isEmpty() :
        "no .state.bin must be written under disabled mode; found ${stateFiles}"
}

return true
