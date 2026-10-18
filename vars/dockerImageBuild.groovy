import org.mlkrtk.ci.Shell

/**
 * Builds a container image with BuildKit and OCI metadata labels.
 *
 * dockerImageBuild(
 *     image: env.DOCKER_IMAGE,              // repository without tag
 *     tag:   env.DOCKER_TAG,                // immutable tag, e.g. <branch>-<sha>
 *     // optional
 *     additionalTags: ['latest'],           // extra tags; avoid mutable tags for deployments
 *     context:    '.',
 *     dockerfile: 'Dockerfile',             // default: <context>/Dockerfile
 *     buildArgs:  [APP_ENV: 'dev'],         // never pass secrets here, they end up in image history
 *     labels:     ['team': 'platform'],
 *     target:     'runtime',                // multi-stage target
 *     platform:   'linux/amd64',
 *     pull:       true,                     // always refresh the base image to pick up security patches
 *     noCache:    false
 * )
 *
 * Returns the primary image reference (image:tag).
 */
def call(Map config = [:]) {
    for (key in ['image', 'tag']) {
        if (!config[key]) {
            error "dockerImageBuild: '${key}' is required"
        }
    }
    List tags = ([config.tag] + (config.additionalTags ?: [])).unique()
    for (t in tags) {
        if (!Shell.isValidTag(t)) {
            error "dockerImageBuild: '${t}' is not a valid image tag"
        }
    }

    String context = config.context ?: '.'
    Map labels = [
        'org.opencontainers.image.created' : new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone('UTC')),
        'org.opencontainers.image.revision': env.GIT_COMMIT,
        'org.opencontainers.image.source'  : env.GIT_URL?.replaceFirst('//[^/@]+@', '//'),   // strip any userinfo
        'org.opencontainers.image.version' : config.tag,
        'io.jenkins.build.url'             : env.BUILD_URL,
    ] + (config.labels ?: [:])

    String args = buildArgs(config.image, tags, context, config.dockerfile ?: "${context}/Dockerfile",
                            config.buildArgs ?: [:], labels, config)
    withEnv(['DOCKER_BUILDKIT=1']) {
        sh(label: "Build ${config.image}:${config.tag}", script: "docker image build ${args}")
    }
    return "${config.image}:${config.tag}"
}

@NonCPS
private static String buildArgs(String image, List tags, String context, String dockerfile,
                                Map buildArgs, Map labels, Map config) {
    List args = []
    if (config.pull != false) args << '--pull'
    if (config.noCache) args << '--no-cache'
    if (config.platform) args << "--platform ${Shell.quote(config.platform)}"
    if (config.target) args << "--target ${Shell.quote(config.target)}"
    args << "--file ${Shell.quote(dockerfile)}"
    tags.each { args << "--tag ${Shell.quote("${image}:${it}")}" }
    labels.findAll { k, v -> v }.each { k, v -> args << "--label ${Shell.quote("${k}=${v}")}" }
    buildArgs.each { k, v -> args << "--build-arg ${Shell.quote("${k}=${v}")}" }
    args << Shell.quote(context)
    return args.join(' ')
}
