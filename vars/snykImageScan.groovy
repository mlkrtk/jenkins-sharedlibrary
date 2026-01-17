def call(String dockerRegistry, String dockerImageTag, String snykCred, String snykOrg) {
    sh """
        if ! command -v snyk > /dev/null; then
            echo "Snyk CLI not found. Installing Snyk CLI..."
            curl --compressed https://downloads.snyk.io/cli/stable/snyk-linux -o snyk
            chmod +x ./snyk
            sudo mv ./snyk /usr/local/bin/
            echo "Snyk CLI installed successfully."
        else
            echo "Snyk CLI is already installed."
            snyk --version
        fi
    """
    
    // Use withCredentials to inject the Snyk token into the environment
    withCredentials([string(
        credentialsId: "$snykCred",
        variable: 'snykToken'
    )]) {
        // Execute Snyk scan within a shell script
        sh """
            export SNYK_TOKEN=${snykToken}
            echo "🔍 Snyk Image scan"
            snyk container test ${dockerRegistry}:${dockerImageTag} --org=${snykOrg} --severity-threshold=high 
            # snyk container monitor ${dockerRegistry}:${dockerImageTag} --org=${snykOrg}
        """
    }
}
