File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"
assert !log.contains("VerifyError") : "no VerifyError; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 1 : "must commit cache when JaCoCo also attached; saw ${committed}"

assert log.contains("jacoco:") || log.contains("jacoco-maven-plugin") :
    "JaCoCo plugin must have run; log:\n${log}"
assert log.contains("Tests run: 1, Failures: 0") :
    "test must pass with both agents attached; log:\n${log}"
assert log.contains("jacoco.agent") :
    "JaCoCo prepare-agent must print argLine; log:\n${log}"

// JaCoCo's argLine is exposed via project property and cull's javaagent is on
// Surefire's <argLine>. The commit of the cache (above) plus the JaCoCo
// instrumentation (next) proves both agents attached successfully.
File jacocoExec = new File(basedir, "target/jacoco.exec")
assert jacocoExec.exists() : "JaCoCo exec data missing — JaCoCo agent did not run"
assert jacocoExec.length() > 0 : "JaCoCo exec data empty"

return true
