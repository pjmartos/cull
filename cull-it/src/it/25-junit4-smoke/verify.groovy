File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"
assert !log.contains("VerifyError") : "no VerifyError expected; log:\n${log}"
assert !log.contains("NoClassDefFoundError") : "no NCDF expected; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache (>=2); saw ${committed}; log:\n${log}"

int calcRuns  = (log =~ /Running io\.pjmartos\.cull\.it\.CalcTest/).count
int otherRuns = (log =~ /Running io\.pjmartos\.cull\.it\.OtherTest/).count

// Soundness: the cold bootstrap run exercises both tests; after Calc is
// mutated the warm run must re-select CalcTest, which both statically
// references and at runtime loads io.pjmartos.cull.it.Calc under the
// JUnit 4 provider. Never miss the impacted test.
assert calcRuns >= 2 :
    "CalcTest must re-run after the Calc change under JUnit 4; saw ${calcRuns}\n${log}"

// Precision: OtherTest has no dependency on Calc, so the Calc change must
// NOT re-select it. It runs once (cold bootstrap) but is not re-selected on
// the warm run, proving CullJunit4Listener attributed the runtime class
// loads per-test so selection discriminates. A relational assertion (not an
// exact count) stays robust to cache state while still failing if selection
// over-selects / degrades to a full run.
assert otherRuns >= 1 :
    "OtherTest must run at least on the cold build; saw ${otherRuns}\n${log}"
assert otherRuns < calcRuns :
    "JUnit 4 selection precision violated: an unaffected test (OtherTest, " +
    "${otherRuns} runs) must be re-selected strictly fewer times than one " +
    "that depends on the changed class (CalcTest, ${calcRuns} runs). Equal " +
    "counts mean cull over-selected / fell back to a full run.\n${log}"

return true
