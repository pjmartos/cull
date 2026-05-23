File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"
assert !log.contains("VerifyError") : "no VerifyError expected; log:\n${log}"
assert !log.contains("NoClassDefFoundError") : "no NCDF expected; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}; log:\n${log}"

assert log.contains("[cull] it-02-prod-class-changed: running all tests (wildcard)") :
    "first run is bootstrap; log:\n${log}"
assert log.contains("[cull] it-02-prod-class-changed: selected 0 test(s)") :
    "second run with no changes must select 0 tests; log:\n${log}"

return true
