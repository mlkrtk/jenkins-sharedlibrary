import org.mlkrtk.ci.Shell
import org.mlkrtk.ci.Snyk

/**
 * Scans the checked-out source with Snyk Code (SAST), optionally Snyk Open Source (SCA) and Snyk IaC.
 * All enabled scans run before any of them can fail the build; SARIF reports are archived.
 *
 * snykCodeScan(
 *     snykCredId: 'snykCred',               // Secret text credential holding the Snyk API token
 *     snykOrg:    env.SNYK_ORG,
 *     // optional
 *     severityThreshold: 'high',            // low | medium | high (Snyk Code has no 'critical')
 *     sca: false,                           // snyk test --all-projects
 *     iac: false,                           // snyk iac test
 *     failOnIssues: true,                   // false => mark build UNSTABLE instead of failing
 *     snykVersion: 'stable'                 // only used if the Snyk CLI has to be installed
 * )
 */
def call(Map config = [:]) {
    Snyk snyk = new Snyk(this, config)
    snyk.validate('snykCodeScan')

    String threshold = "--severity-threshold=${Shell.quote(config.severityThreshold ?: 'high')}"
    String reports = Snyk.REPORT_DIR
    sh "mkdir -p ${reports}"

    List results = []
    results << ['Snyk Code (SAST)',
                snyk.run('Snyk Code test', "code test ${threshold} --sarif-file-output=${reports}/code.sarif")]
    if (config.sca) {
        results << ['Snyk Open Source (SCA)',
                    snyk.run('Snyk Open Source test', "test --all-projects ${threshold} --sarif-file-output=${reports}/sca.sarif")]
    }
    if (config.iac) {
        results << ['Snyk IaC',
                    snyk.run('Snyk IaC test', "iac test ${threshold} --sarif-file-output=${reports}/iac.sarif")]
    }

    archiveArtifacts artifacts: "${reports}/*.sarif", allowEmptyArchive: true, fingerprint: false
    for (result in results) {
        snyk.check(result[0] as String, result[1] as int)
    }
}
