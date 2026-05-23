File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 4 : "both modules must commit cache on both runs (>=4); saw ${committed}"

int sharedRuns     = (log =~ /Running io\.pjmartos\.cull\.it\.SharedTest/).count
int consumerRuns   = (log =~ /Running io\.pjmartos\.cull\.it\.ConsumerTest/).count
int standaloneRuns = (log =~ /Running io\.pjmartos\.cull\.it\.StandaloneTest/).count

// Soundness: a change to upstream Shared must re-run the upstream test that
// covers it AND the downstream ConsumerTest, which both statically references
// and at runtime loads io.pjmartos.cull.it.Shared. Never miss an impacted test.
assert sharedRuns >= 2 :
    "SharedTest must re-run after the Shared change; saw ${sharedRuns}\n${log}"
assert consumerRuns >= 2 :
    "ConsumerTest must re-run — it depends on the changed upstream Shared; saw ${consumerRuns}\n${log}"

// Cross-module precision (the headline cross-module guarantee, end-to-end through the
// real reactor + CullParticipant + agent + SessionState cross-classloader
// handoff): StandaloneTest has no dependency on Shared, so the upstream change
// must NOT re-select it. It runs once (the cold bootstrap run) but is not
// re-selected on the warm run; a genuinely-affected test (ConsumerTest) is.
// A relational assertion (not an exact count) stays robust to cache state
// while still failing if downstream over-selects / degrades to a full run.
assert standaloneRuns >= 1 :
    "StandaloneTest must run at least on the cold build; saw ${standaloneRuns}\n${log}"
assert standaloneRuns < consumerRuns :
    "cross-module precision violated: an unaffected downstream test (StandaloneTest, " +
    "${standaloneRuns} runs) must be re-selected strictly fewer times than one that " +
    "depends on the changed upstream class (ConsumerTest, ${consumerRuns} runs). Equal " +
    "counts mean downstream over-selected / fell back to a full run.\n${log}"

return true
