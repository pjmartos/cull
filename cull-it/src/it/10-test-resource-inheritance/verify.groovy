File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}"

int inherited = (log =~ /Running io\.pjmartos\.cull\.it\.InheritedFixtureTest/).count
int secondInherited = (log =~ /Running io\.pjmartos\.cull\.it\.SecondInheritedTest/).count
int isolated = (log =~ /Running io\.pjmartos\.cull\.it\.IsolatedTest/).count

assert inherited >= 2 :
    "InheritedFixtureTest re-runs because fixture.txt (loaded via inherited helper) changed; saw ${inherited}"
assert secondInherited >= 2 :
    "SecondInheritedTest also reads fixture.txt via FixtureSupport and must re-run; saw ${secondInherited}"
assert isolated == 1 :
    "IsolatedTest depends only on other.txt and must NOT re-run; saw ${isolated}; log:\n${log}"

return true
