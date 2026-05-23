File buildLog = new File(basedir, "build.log")
assert buildLog.exists()
String log = buildLog.text

int committed = (log =~ /\[cull\] committed cache/).count
assert committed == 2 : "expected commit on both runs; saw ${committed}"

int wildcards = (log =~ /\[cull\][^\n]*running all tests/).count
assert wildcards >= 1 : "first run should be a wildcard bootstrap; saw ${wildcards}"

int zeroSelected = (log =~ /selected 0 test\(s\)/).count
assert zeroSelected >= 1 : "second run must run zero tests because nothing changed; saw ${zeroSelected}"

return true
