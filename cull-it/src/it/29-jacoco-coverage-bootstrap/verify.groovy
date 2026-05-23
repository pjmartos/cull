import groovy.xml.XmlSlurper

File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "cull agent must not be disabled; log:\n${log}"
assert !log.contains("VerifyError") : "no VerifyError; log:\n${log}"

// Run 1 bootstraps the cache and the coverage baseline (everything runs).
assert log.contains("[cull] it-29-jacoco-coverage-bootstrap: running all tests (wildcard)") :
    "first run must bootstrap the cache; log:\n${log}"

// Run 2: the .exec baseline was deleted (graph kept) and Alpha mutated, so the
// graph alone would reselect only AlphaTest. cull must instead detect the
// missing baseline and run the WHOLE suite once to re-capture it.
assert log.contains(
        "[cull] it-29-jacoco-coverage-bootstrap: running all tests (coverage baseline bootstrap)") :
    "second run must force a full bootstrap when the coverage baseline is missing; log:\n${log}"
assert !log.contains("[cull] it-29-jacoco-coverage-bootstrap: selected 1 test(s)") :
    "second run must NOT settle for the AlphaTest-only subset; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}; log:\n${log}"

// The core assertion: after run 2 the JaCoCo report must show Beta covered.
// Without the bootstrap, only AlphaTest would have run on this clean build and
// Beta would report zero coverage.
File reportXml = new File(basedir, "target/site/jacoco/jacoco.xml")
assert reportXml.exists() : "JaCoCo XML report missing — report goal did not run"

def parser = new XmlSlurper()
parser.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false)
parser.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
def report = parser.parse(reportXml)

int betaCovered = -1
report.'package'.findAll { it.@name == 'io/pjmartos/cull/it' }.each { pkg ->
    pkg.'class'.findAll { it.@name == 'io/pjmartos/cull/it/Beta' }.each { cls ->
        cls.counter.findAll { it.@type == 'INSTRUCTION' }.each { c ->
            betaCovered = (c.@covered).toInteger()
        }
    }
}

assert betaCovered > 0 : "Beta coverage was lost on the clean partial run (covered=${betaCovered}); cull failed to bootstrap the missing coverage baseline by running the full suite"

return true
