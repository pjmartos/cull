import groovy.xml.XmlSlurper

File buildLog = new File(basedir, "build.log")
assert buildLog.exists() : "build.log missing"
String log = buildLog.text

assert !log.contains("agent disabled") : "cull agent must not be disabled; log:\n${log}"
assert !log.contains("VerifyError") : "no VerifyError; log:\n${log}"

// Run 1 bootstraps (everything runs); run 2 reselects only AlphaTest because
// the Alpha mutation rehashes, leaving BetaTest unselected this round.
assert log.contains("[cull] it-28-jacoco-coverage-retention: running all tests (wildcard)") :
    "first run must bootstrap; log:\n${log}"
assert log.contains("[cull] it-28-jacoco-coverage-retention: selected 1 test(s)") :
    "second run must reselect exactly AlphaTest; log:\n${log}"

// cull must have seeded the retained baseline back into jacoco.exec on run 2.
assert log.contains("[cull] restored coverage baseline for it-28-jacoco-coverage-retention") :
    "cull must restore the coverage baseline; log:\n${log}"

int committed = (log =~ /\[cull\] committed cache/).count
assert committed >= 2 : "both runs must commit cache; saw ${committed}; log:\n${log}"

// The core assertion: after run 2 (where ONLY AlphaTest executed) the JaCoCo
// report must still show Beta covered, proving the baseline was retained and
// merged rather than overwritten by the subset.
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

assert betaCovered > 0 : "Beta coverage was not retained across the partial run (covered=${betaCovered}); cull failed to merge the cached baseline into jacoco.exec"

return true
