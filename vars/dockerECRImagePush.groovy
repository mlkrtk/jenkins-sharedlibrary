import org.mlkrtk.ci.Shell
import org.mlkrtk.ci.Tools

/**
 * Pushes a locally built image to Amazon ECR.
 *
 * dockerECRImagePush(
 *     image:  env.DOCKER_IMAGE,             // <account>.dkr.ecr.<region>.amazonaws.com/<repo>
 *     tag:    env.DOCKER_TAG,
 *     region: 'ap-south-1',
 *     // optional
 *     awsCredId: 'awsCred',                 // "AWS Credentials" credential; omit to use the agent's
 *                                           // IAM role (instance profile / IRSA), which is preferred
 *     additionalTags: ['latest'],           // must have been created by dockerImageBuild
 *     createRepo:     false,                // create the ECR repo if missing; prefer managing it in IaC
 *     tagMutability:  'IMMUTABLE',          // only used with createRepo
 *     awsCliVersion:  '2.17.0'              // only used if the AWS CLI has to be installed
 * )
 *
 * Returns the pushed image digest reference (image@sha256:...), suitable for pinning deployments.
 */
def call(Map config = [:]) {
    for (key in ['image', 'tag', 'region']) {
        if (!config[key]) {
            error "dockerECRImagePush: '${key}' is required"
        }
    }
    String image = config.image
    String registry = image.tokenize('/')[0]
    if (!(registry ==~ /\d{12}\.dkr\.ecr\.[a-z0-9-]+\.amazonaws\.com(\.cn)?/)) {
        error "dockerECRImagePush: '${image}' is not an ECR image (<account>.dkr.ecr.<region>.amazonaws.com/<repo>)"
    }
    List tags = ([config.tag] + (config.additionalTags ?: [])).unique()
    for (t in tags) {
        if (!Shell.isValidTag(t)) {
            error "dockerECRImagePush: '${t}' is not a valid image tag"
        }
    }

    String awsBin = new Tools(this).ensure('awscli', config.awsCliVersion as String)
    String digest = ''
    // Per-build DOCKER_CONFIG keeps the registry token out of the agent user's ~/.docker/config.json.
    withEnv(["PATH+CI_TOOLS=${awsBin}",
             "AWS_REGION=${config.region}",
             "AWS_DEFAULT_REGION=${config.region}",
             "DOCKER_CONFIG=${pwd(tmp: true)}/docker-config"]) {
        withAwsCredentials(config.awsCredId as String) {
            if (config.createRepo) {
                ensureRepository(image.substring(registry.length() + 1), config.tagMutability ?: 'IMMUTABLE')
            }
            try {
                sh(label: 'ECR login',
                   script: "aws ecr get-login-password | docker login --username AWS --password-stdin ${Shell.quote(registry)}")
                for (t in tags) {
                    sh(label: "Push ${image}:${t}", script: "docker image push ${Shell.quote("${image}:${t}")}")
                }
                digest = sh(label: 'Resolve image digest', returnStdout: true, script: """
                    docker image inspect --format '{{range .RepoDigests}}{{println .}}{{end}}' ${Shell.quote("${image}:${config.tag}")} \\
                        | grep -F ${Shell.quote("${image}@")} | head -n 1
                """).trim()
            } finally {
                sh(label: 'ECR logout', script: "docker logout ${Shell.quote(registry)} >/dev/null 2>&1 || true")
            }
        }
    }
    echo "Pushed ${image}:${config.tag} (${digest ?: 'digest unavailable'})"
    return digest
}

private void withAwsCredentials(String credId, Closure body) {
    if (credId) {
        withCredentials([[$class: 'AmazonWebServicesCredentialsBinding', credentialsId: credId]]) {
            body()
        }
    } else {
        body()
    }
}

private void ensureRepository(String repo, String tagMutability) {
    String q = Shell.quote(repo)
    sh(label: "Ensure ECR repository ${repo}", script: """
        if aws ecr describe-repositories --repository-names ${q} >/dev/null 2>&1; then
            echo "ECR repository exists: "${q}
        else
            aws ecr create-repository --repository-name ${q} \\
                --image-tag-mutability ${Shell.quote(tagMutability)} \\
                --image-scanning-configuration scanOnPush=true \\
                --encryption-configuration encryptionType=AES256 >/dev/null
            echo "Created ECR repository: "${q}
        fi
    """)
}
