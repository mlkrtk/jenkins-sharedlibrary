import groovy.json.JsonOutput
import groovy.json.JsonSlurperClassic
import org.mlkrtk.ci.ManifestUpdater

/**
 * GitOps: sets an image tag in kustomization.yaml on a bot branch and opens (or updates) a GitHub pull
 * request against the target branch. Uses the GitHub REST API, so no `gh` CLI is required.
 *
 * updateManifestWithPR(
 *     repoUrl:   'https://github.com/org/manifests.git',   // github.com or GitHub Enterprise, HTTPS
 *     branch:    'develop',                                // PR base branch
 *     gitCredId: 'githubCred',                             // Username with password (password = PAT)
 *     path:      'develop/tetris/overlays/dev',
 *     image:     env.DOCKER_IMAGE,
 *     tag:       env.DOCKER_TAG,
 *     // optional
 *     ghTokenCredId: 'githubToken',                        // Secret text PAT for the API;
 *                                                          // defaults to the gitCredId password
 *     sourceCommit:  env.GIT_COMMIT,
 *     prTitle:  '...',
 *     prBody:   '...',
 *     prBranch: 'ci/...',                                  // default: ci/<path>/<tag>
 *     kustomizeImageName: 'tetris',
 *     gitUserName:  'Jenkins-CI-Bot',
 *     gitUserEmail: 'jenkins@opqtech.com'
 * )
 *
 * Token permissions: contents read/write and pull requests read/write on the manifest repo.
 * Returns [changed: boolean, commit: String, branch: String, prUrl: String].
 */
def call(Map config = [:]) {
    ManifestUpdater updater = new ManifestUpdater(this, config)
    updater.validate('updateManifestWithPR')
    Map github = githubCoordinates(config.repoUrl as String)
    if (!github) {
        error "updateManifestWithPR: cannot derive the GitHub repository from '${config.repoUrl}'"
    }
    String prBranch = config.prBranch ?: "ci/${slug(config.path as String)}/${slug(config.tag as String)}"

    updater.checkoutRepo()
    if (!updater.applyUpdate()) {
        echo "Manifests already reference ${config.image}:${config.tag}; no pull request needed."
        return [changed: false, commit: null, branch: prBranch, prUrl: null]
    }
    // The PR branch is bot-owned, so force-pushing is safe and makes re-runs update the existing PR.
    updater.push(prBranch, true)
    String commit = updater.head()

    String prUrl = createPullRequest(config, github, prBranch, updater.imageName)
    echo "Pull request: ${prUrl}"
    return [changed: true, commit: commit, branch: prBranch, prUrl: prUrl]
}

private String createPullRequest(Map config, Map github, String head, String imageName) {
    String tmp = pwd(tmp: true)
    String owner = github.repo.tokenize('/')[0]
    writeFile file: "${tmp}/pr-request.json", text: JsonOutput.toJson([
        title: config.prTitle ?: "chore(${imageName}): deploy ${config.tag} to ${config.path}",
        body : config.prBody ?: defaultBody(config),
        head : head,
        base : config.branch,
    ])

    def tokenBinding = config.ghTokenCredId ?
        string(credentialsId: config.ghTokenCredId, variable: 'GH_TOKEN') :
        usernamePassword(credentialsId: config.gitCredId, usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')

    String prUrl = null
    withCredentials([tokenBinding]) {
        withEnv(["PULLS_API=${github.api}/repos/${github.repo}/pulls",
                 "PR_HEAD=${owner}:${head}",
                 "TMP_DIR=${tmp}"]) {
            // The token is fed to curl through --config on stdin so it never shows up in process arguments.
            String status = sh(label: 'Create pull request', returnStdout: true, script: '''
                set -eu
                printf 'header = "Authorization: Bearer %s"\\n' "$GH_TOKEN" | curl -sS --retry 3 --config - \\
                    -X POST -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' \\
                    -o "$TMP_DIR/pr-response.json" -w '%{http_code}' \\
                    --data @"$TMP_DIR/pr-request.json" "$PULLS_API"
            ''').trim()

            if (status == '201') {
                prUrl = parseJson(readFile("${tmp}/pr-response.json")).html_url
                echo "Created pull request ${prUrl}"
            } else if (status == '422') {
                // Typically "A pull request already exists"; the force-push above has already updated it.
                sh(label: 'Find existing pull request', script: '''
                    set -eu
                    printf 'header = "Authorization: Bearer %s"\\n' "$GH_TOKEN" | curl -fsS --retry 3 --config - -G \\
                        -H 'Accept: application/vnd.github+json' -H 'X-GitHub-Api-Version: 2022-11-28' \\
                        --data-urlencode "head=$PR_HEAD" --data-urlencode 'state=open' \\
                        -o "$TMP_DIR/pr-list.json" "$PULLS_API"
                ''')
                List open = parseJson(readFile("${tmp}/pr-list.json")) as List
                if (!open) {
                    error "GitHub rejected the pull request (HTTP 422): ${readFile("${tmp}/pr-response.json")}"
                }
                prUrl = open[0].html_url
                echo "Updated existing pull request ${prUrl}"
            } else {
                error "GitHub API returned HTTP ${status} creating the pull request: ${readFile("${tmp}/pr-response.json")}"
            }
        }
    }
    return prUrl
}

private String defaultBody(Map config) {
    List rows = ["| Image | `${config.image}:${config.tag}` |",
                 "| Path | `${config.path}` |"]
    if (config.sourceCommit) {
        rows << "| Source commit | `${config.sourceCommit}` |"
    }
    if (env.BUILD_URL) {
        rows << "| Jenkins build | [${env.JOB_NAME} #${env.BUILD_NUMBER}](${env.BUILD_URL}) |"
    }
    return (['Automated image update from Jenkins.', '', '| | |', '|---|---|'] + rows).join('\n')
}

/** https://github.com/org/repo(.git) -> [api: 'https://api.github.com', repo: 'org/repo']; GHES uses /api/v3. */
@NonCPS
private static Map githubCoordinates(String repoUrl) {
    def m = repoUrl =~ /^https:\/\/(?:[^@\/]+@)?([^\/:]+)\/([^\/]+\/[^\/]+?)(?:\.git)?\/?$/
    if (!m.matches()) {
        return null
    }
    String host = m.group(1)
    return [api : host == 'github.com' ? 'https://api.github.com' : "https://${host}/api/v3".toString(),
            repo: m.group(2)]
}

@NonCPS
private static String slug(String value) {
    return value.replaceAll('[^A-Za-z0-9._-]+', '-').replaceAll('^-+|-+$', '')
}

@NonCPS
private static Object parseJson(String text) {
    return new JsonSlurperClassic().parseText(text)
}
