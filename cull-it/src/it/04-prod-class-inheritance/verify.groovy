File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}"

int dogRuns   = (log =~ /Running io\.pjmartos\.cull\.it\.DogTest/).count
int catRuns   = (log =~ /Running io\.pjmartos\.cull\.it\.CatTest/).count
int stoneRuns = (log =~ /Running io\.pjmartos\.cull\.it\.StoneTest/).count

assert dogRuns == 2  : "DogTest must run twice; saw ${dogRuns}"
assert catRuns == 2  : "CatTest must run twice (inherits Animal); saw ${catRuns}"
assert stoneRuns == 1 : "StoneTest must NOT re-run after Animal change; saw ${stoneRuns}"

return true
