package org.mlkrtk.ci

/**
 * Makes CLI tools available on the agent without sudo.
 *
 * Tools already on PATH are used as-is (the preferred setup is to bake them into the agent image).
 * Otherwise they are downloaded once into ~/.cache/ci-tools and reused by later builds.
 */
class Tools implements Serializable {
    private final def script

    Tools(script) {
        this.script = script
    }

    /**
     * Ensures `tool` (snyk | awscli) is installed and returns the directory to prepend to PATH:
     *   withEnv(["PATH+CI_TOOLS=${new Tools(this).ensure('snyk')}"]) { ... }
     */
    String ensure(String tool, String version = null) {
        String installer = "${script.pwd(tmp: true)}/install-${tool}.sh"
        script.writeFile(file: installer, text: script.libraryResource("org/mlkrtk/ci/install-${tool}.sh"))

        String binDir = ''
        script.withEnv(["TOOL_VERSION=${version ?: ''}"]) {
            binDir = script.sh(label: "Ensure ${tool} CLI", returnStdout: true,
                               script: "bash ${Shell.quote(installer)}").trim()
        }
        return binDir
    }
}
