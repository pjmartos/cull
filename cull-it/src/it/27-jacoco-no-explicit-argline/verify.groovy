File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "cull agent must not be disabled; log:\n${log}"
assert !log.contains("could not open") :
    "an unresolved @{argLine} reached the JVM and crashed the fork; log:\n${log}"
assert !log.contains("VM crash or System.exit") :
    "the fork must start cleanly; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 1 : "cull must commit cache with JaCoCo also attached; saw ${committed}"

assert log.contains("jacoco-maven-plugin") || log.contains("jacoco:") :
    "JaCoCo plugin must have run; log:\n${log}"
assert log.contains("Tests run: 1, Failures: 0") :
    "the test must pass with both agents attached; log:\n${log}"

// The core regression assertion: JaCoCo's agent actually attached and recorded
// coverage even though the project never set a Surefire <argLine>. If cull had
// overwritten (rather than augmented with @{argLine}) the JaCoCo argLine, this
// file would be absent or empty.
File jacocoExec = new File(basedir, "target/jacoco.exec")
assert jacocoExec.exists() :
    "JaCoCo exec data missing — cull overwrote the JaCoCo argLine instead of augmenting it"
assert jacocoExec.length() > 0 : "JaCoCo exec data empty — JaCoCo agent did not record"

return true
