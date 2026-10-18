import org.mlkrtk.ci.Shell
import org.mlkrtk.ci.Snyk

/**
 * Scans a locally built image with Snyk Container. Run it before pushing so vulnerable images never
 * reach the registry. The SARIF report is archived.
 *
 * snykImageScan(
 *     image:      env.DOCKER_IMAGE,
 *     tag:        env.DOCKER_TAG,
 *     snykCredId: 'snykCred',               // Secret text credential holding the Snyk API token
 *     snykOrg:    env.SNYK_ORG,
 *     // optional
 *     severityThreshold: 'critical',        // low | medium | high | critical
 *     dockerfile: 'Dockerfile',             // enables base-image upgrade advice; false to disable
 *     excludeAppVulns: false,               // scan OS packages only
 *     failOnIssues: true,                   // false => mark build UNSTABLE instead of failing
 *     monitor: false,                       // snapshot to the Snyk UI for continuous monitoring
 *                                           // (enable on deployable branches only)
 *     snykVersion: 'stable'                 // only used if the Snyk CLI has to be installed
 * )
 */
def call(Map config = [:]) {
    Snyk snyk = new Snyk(this, config)
    snyk.validate('snykImageScan')
    for (key in ['image', 'tag']) {
        if (!config[key]) {
            error "snykImageScan: '${key}' is required"
        }
    }

    String imageRef = Shell.quote("${config.image}:${config.tag}")
    String dockerfile = config.dockerfile == false ? null : (config.dockerfile ?: 'Dockerfile')
    String common = ''
    if (dockerfile && fileExists(dockerfile)) {
        common += " --file=${Shell.quote(dockerfile)}"
    }
    if (config.excludeAppVulns) {
        common += ' --exclude-app-vulns'
    }

    String reports = Snyk.REPORT_DIR
    sh "mkdir -p ${reports}"
    int rc = snyk.run('Snyk Container test',
                      "container test ${imageRef}${common} " +
                      "--severity-threshold=${Shell.quote(config.severityThreshold ?: 'critical')} " +
                      "--sarif-file-output=${reports}/container.sarif")
    archiveArtifacts artifacts: "${reports}/container.sarif", allowEmptyArchive: true, fingerprint: false

    if (config.monitor && rc in [0, 1]) {
        int monitorRc = snyk.run('Snyk Container monitor', "container monitor ${imageRef}${common}")
        if (monitorRc != 0) {
            unstable "Snyk Container monitor failed with exit code ${monitorRc}"
        }
    }
    snyk.check('Snyk Container', rc)
}
