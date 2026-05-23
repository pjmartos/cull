File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") :
    "agent must not be disabled on this JVM (regression for StackMapTable handling); log:\n${log}"
assert !log.contains("VerifyError") : "no VerifyError; log:\n${log}"
assert !log.contains("NoClassDefFoundError") : "no NCDF; log:\n${log}"
assert !log.contains("I/O hooks unavailable") :
    "I/O hooks must install on supported JDK (degraded mode means hooks broke); log:\n${log}"
assert log.contains("[cull] committed cache") : "cache must be committed; log:\n${log}"

return true
