File buildLog = new File(basedir, "build.log")
assert buildLog.exists()
String log = buildLog.text

assert log.contains("running all tests (forkCount=0 / forkMode=never)") :
    "fork-zero must opt out with the specific wildcard reason; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed == 0 : "no commit must happen under forkCount=0; saw ${committed}; log:\n${log}"

return true
