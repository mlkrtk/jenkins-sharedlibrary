package org.mlkrtk.ci

/** Runs the Snyk CLI with the API token injected from Jenkins credentials and interprets its exit codes. */
class Snyk implements Serializable {
    static final String REPORT_DIR = 'snyk-reports'

    private final def script
    private final Map config

    Snyk(script, Map config) {
        this.script = script
        this.config = config
    }

    void validate(String step) {
        for (key in ['snykCredId', 'snykOrg']) {
            if (!config[key]) {
                script.error("${step}: '${key}' is required")
            }
        }
    }

    /** Runs `snyk <args> --org=<org>` and returns the CLI exit code. `args` must already be shell-quoted. */
    int run(String label, String args) {
        String binDir = new Tools(script).ensure('snyk', config.snykVersion as String)
        int rc = 0
        script.withEnv(["PATH+CI_TOOLS=${binDir}"]) {
            // The CLI reads SNYK_TOKEN from the environment, so the token never appears in the script or log.
            script.withCredentials([script.string(credentialsId: config.snykCredId, variable: 'SNYK_TOKEN')]) {
                rc = script.sh(label: label, returnStatus: true,
                               script: "snyk ${args} --org=${Shell.quote(config.snykOrg)}")
            }
        }
        return rc
    }

    /** Snyk exit codes: 0 clean, 1 issues at/above threshold, 2 CLI error, 3 no supported project. */
    void check(String scan, int rc) {
        if (rc == 0) {
            script.echo("${scan}: no issues at or above the severity threshold")
        } else if (rc == 1) {
            String message = "${scan}: issues found at or above the severity threshold (see ${REPORT_DIR}/ artifacts)"
            if (config.failOnIssues == false) {
                script.unstable(message)
            } else {
                script.error(message)
            }
        } else if (rc == 3) {
            script.echo("${scan}: no supported project found, skipped")
        } else {
            script.error("${scan}: Snyk CLI failed with exit code ${rc}")
        }
    }
}
