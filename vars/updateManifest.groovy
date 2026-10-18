import org.mlkrtk.ci.ManifestUpdater

/**
 * GitOps: sets an image tag in kustomization.yaml and pushes straight to the target branch.
 * Use updateManifestWithPR for branches that need review (staging/production).
 *
 * updateManifest(
 *     repoUrl:   'https://github.com/org/manifests.git',   // HTTPS
 *     branch:    'develop',
 *     gitCredId: 'githubCred',                             // Username with password (password = PAT)
 *     path:      'develop/tetris/overlays/dev',            // kustomization file, or directory to search
 *     image:     env.DOCKER_IMAGE,
 *     tag:       env.DOCKER_TAG,
 *     // optional
 *     sourceCommit:       env.GIT_COMMIT,                  // recorded in the commit message
 *     kustomizeImageName: 'tetris',                        // `name:` of the images entry, when the
 *                                                          // default match (name/newName) is not enough
 *     gitUserName:  'Jenkins-CI-Bot',
 *     gitUserEmail: 'jenkins@opqtech.com'
 * )
 *
 * Returns [changed: boolean, commit: String, branch: String].
 */
def call(Map config = [:]) {
    ManifestUpdater updater = new ManifestUpdater(this, config)
    updater.validate('updateManifest')
    updater.checkoutRepo()

    if (!updater.applyUpdate()) {
        echo "Manifests already reference ${config.image}:${config.tag}; nothing to push."
        return [changed: false, commit: null, branch: config.branch]
    }
    updater.push(config.branch as String, false)

    String commit = updater.head()
    echo "Pushed ${commit} to ${config.branch}"
    return [changed: true, commit: commit, branch: config.branch]
}
