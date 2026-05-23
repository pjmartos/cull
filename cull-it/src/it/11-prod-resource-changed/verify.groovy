File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "agent must not be disabled; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}"

int bannerRuns = (log =~ /Running io\.pjmartos\.cull\.it\.BannerTest/).count
int staticRuns = (log =~ /Running io\.pjmartos\.cull\.it\.StaticTest/).count

assert bannerRuns >= 2 : "BannerTest re-runs after banner.txt change; saw ${bannerRuns}"
assert staticRuns == 1 :
    "StaticTest depends only on static.txt and must NOT re-run; saw ${staticRuns}; log:\n${log}"

return true
