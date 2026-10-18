package org.mlkrtk.ci

/**
 * GitOps logic shared by updateManifest and updateManifestWithPR: clones the manifest repo into an
 * isolated directory, bumps one image tag in its kustomization file(s), commits and pushes.
 *
 * Authentication is HTTPS with a Jenkins username/password credential (password = PAT). git gets the
 * token from a credential helper that reads it from the environment, so it never lands in .git/config,
 * the remote URL, process arguments or the build log.
 */
class ManifestUpdater implements Serializable {
    static final String WORK_DIR = '.ci/manifests'

    private final def script
    private final Map config

    ManifestUpdater(script, Map config) {
        this.script = script
        this.config = config
    }

    void validate(String step) {
        for (key in ['repoUrl', 'branch', 'gitCredId', 'path', 'image', 'tag']) {
            if (!config[key]) {
                script.error("${step}: '${key}' is required")
            }
        }
        if (!(config.repoUrl ==~ /https:\/\/.+/)) {
            script.error("${step}: repoUrl must be an HTTPS URL, got '${config.repoUrl}'")
        }
        if (!Shell.isValidTag(config.tag)) {
            script.error("${step}: '${config.tag}' is not a valid image tag")
        }
    }

    String getImageName() {
        return config.image.toString().tokenize('/').last()
    }

    void checkoutRepo() {
        script.dir(WORK_DIR) {
            // changelog/poll off: manifest commits must not show up in, or trigger, the app pipeline.
            script.checkout(changelog: false, poll: false, scm: [
                $class           : 'GitSCM',
                branches         : [[name: "refs/heads/${config.branch}".toString()]],
                extensions       : [[$class: 'CleanBeforeCheckout'], [$class: 'CloneOption', noTags: true, shallow: false]],
                userRemoteConfigs: [[credentialsId: config.gitCredId, url: config.repoUrl]],
            ])
        }
    }

    /** Updates the image tag and commits. Returns false when the manifests already reference the tag. */
    boolean applyUpdate() {
        String tmp = script.pwd(tmp: true)
        String updater = "${tmp}/update-kustomize-image.sh"
        String messageFile = "${tmp}/manifest-commit-message.txt"
        script.writeFile(file: updater, text: script.libraryResource('org/mlkrtk/ci/update-kustomize-image.sh'))
        script.writeFile(file: messageFile, text: commitMessage())

        String userName = config.gitUserName ?: 'Jenkins-CI-Bot'
        String userEmail = config.gitUserEmail ?: 'jenkins@opqtech.com'
        boolean changed = false
        script.dir(WORK_DIR) {
            script.withEnv([
                "UPDATER=${updater}",
                "MANIFEST_PATH=${config.path}",
                "IMAGE=${config.image}",
                "TAG=${config.tag}",
                "KUSTOMIZE_IMAGE_NAME=${config.kustomizeImageName ?: ''}",
                "COMMIT_MESSAGE_FILE=${messageFile}",
                "GIT_AUTHOR_NAME=${userName}", "GIT_COMMITTER_NAME=${userName}",
                "GIT_AUTHOR_EMAIL=${userEmail}", "GIT_COMMITTER_EMAIL=${userEmail}",
            ]) {
                script.sh(label: 'Update image tag', script: '''
                    set -eu
                    bash "$UPDATER" "$MANIFEST_PATH" "$IMAGE" "$TAG" "$KUSTOMIZE_IMAGE_NAME"
                    git add -A -- "$MANIFEST_PATH"
                ''')
                changed = script.sh(label: 'Check for changes', returnStatus: true, script: 'git diff --cached --quiet') != 0
                if (changed) {
                    script.sh(label: 'Commit manifest change', script: 'git commit --quiet -F "$COMMIT_MESSAGE_FILE" && git log -1 --stat')
                }
            }
        }
        return changed
    }

    /**
     * Pushes HEAD to `targetBranch`. With force=false (shared branch) a rejected push is rebased onto the
     * latest remote state and retried; force=true is only for bot-owned branches such as PR branches.
     */
    void push(String targetBranch, boolean force) {
        script.dir(WORK_DIR) {
            script.withCredentials([script.usernamePassword(credentialsId: config.gitCredId,
                                                            usernameVariable: 'GIT_USERNAME',
                                                            passwordVariable: 'GIT_PASSWORD')]) {
                script.withEnv(["TARGET_BRANCH=${targetBranch}", "FORCE_PUSH=${force}"]) {
                    script.sh(label: "git push ${targetBranch}", script: '''
                        set -u
                        # Empty helper resets inherited helpers; ours reads the token from env at call time.
                        git config --local --unset-all credential.helper || true
                        git config --local --add credential.helper ''
                        git config --local --add credential.helper '!f() { test "$1" = get && printf "username=%s\\npassword=%s\\n" "$GIT_USERNAME" "$GIT_PASSWORD"; }; f'

                        if [ "$FORCE_PUSH" = true ]; then
                            exec git push --force origin "HEAD:refs/heads/${TARGET_BRANCH}"
                        fi

                        for attempt in 1 2 3; do
                            git push origin "HEAD:refs/heads/${TARGET_BRANCH}" && exit 0
                            echo "Push rejected (attempt ${attempt}/3), rebasing onto latest ${TARGET_BRANCH}..." >&2
                            if ! { git fetch --quiet origin "${TARGET_BRANCH}" && git rebase FETCH_HEAD; }; then
                                git rebase --abort 2>/dev/null || true
                                echo "ERROR: could not rebase onto ${TARGET_BRANCH}" >&2
                                exit 1
                            fi
                            sleep $((attempt * 5))
                        done
                        echo "ERROR: could not push to ${TARGET_BRANCH} after 3 attempts" >&2
                        exit 1
                    ''')
                }
            }
        }
    }

    String head() {
        String sha = ''
        script.dir(WORK_DIR) {
            sha = script.sh(returnStdout: true, script: 'git rev-parse HEAD').trim()
        }
        return sha
    }

    private String commitMessage() {
        List lines = ["chore(${imageName}): deploy ${config.tag}", '',
                      "Image: ${config.image}:${config.tag}",
                      "Path: ${config.path}"]
        if (config.sourceCommit) {
            lines << "Source commit: ${config.sourceCommit}"
        }
        if (script.env.BUILD_URL) {
            lines << "Jenkins build: ${script.env.BUILD_URL}"
        }
        return lines.join('\n') + '\n'
    }
}
