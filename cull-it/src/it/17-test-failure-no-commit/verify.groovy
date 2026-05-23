File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert log.contains("[cull] rolled back") : "failed test must trigger rollback; log:\n${log}"
assert !log.contains("[cull] committed cache") : "must not commit on test failure; log:\n${log}"
assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"

return true
