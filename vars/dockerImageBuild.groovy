def call (String dockerRegistry, String dockerImageTag) {
  sh """
    docker buildx build --platform linux/amd64,linux/arm64 -t $dockerRegistry .
    docker image tag $dockerRegistry $dockerRegistry:$dockerImageTag
    docker image tag $dockerRegistry $dockerRegistry:latest
    """
}
