File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}"

int widgetRuns   = (log =~ /Running io\.pjmartos\.cull\.it\.WidgetTest/).count
int isolatedRuns = (log =~ /Running io\.pjmartos\.cull\.it\.IsolatedTest/).count

assert widgetRuns == 2   : "WidgetTest re-runs because TestHelper changed; saw ${widgetRuns}"
assert isolatedRuns == 1 : "IsolatedTest must NOT re-run after TestHelper change; saw ${isolatedRuns}"

return true
