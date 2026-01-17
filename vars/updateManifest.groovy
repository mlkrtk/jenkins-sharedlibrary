#!/usr/bin/env groovy

def call(String manifestRepo, String manifestBranch, String gitCredId, String manifestPath, 
         String newImageName, String newImageTag, String appRepoCommitId = '', 
         String prTitle = '', String prBody = '', String ghTokenCredId = '') {
    
   
    echo "=== Starting Manifest Update Process (Direct Push) ==="
    echo "Repository: ${manifestRepo}"
    echo "Branch: ${manifestBranch}"
    echo "New Image: ${newImageName}:${newImageTag}"
    echo "App Repo Commit ID: ${appRepoCommitId ?: 'Not provided'}"
    echo "Manifest Path: ${manifestPath}"
      
    echo "=== Checking out manifest repository ==="
    checkout([
        $class: 'GitSCM',
        branches: [[name: "${manifestBranch}"]],
        extensions: [[$class: 'CleanBeforeCheckout']],
        userRemoteConfigs: [[
            credentialsId: "${gitCredId}",
            url: "${manifestRepo}"
        ]]
    ])
    
    echo "=== Updating manifest files ==="
    withEnv([
        "NEW_IMAGE_NAME=${newImageName}",
        "NEW_IMAGE_TAG=${newImageTag}",
        "MANIFEST_PATH=${manifestPath}"
    ]) {
        sh '''
                set -e
                
                # Configure Git
                git config user.name "Jenkins-CI-Bot"
                git config user.email "jenkins@opqtech.com"
                
                NEW_IMAGE="${NEW_IMAGE_NAME}:${NEW_IMAGE_TAG}"
                
                echo "Target image: ${NEW_IMAGE}"
                echo "Manifest path: ${MANIFEST_PATH}"
                 
                if [ -f "${MANIFEST_PATH}" ]; then
                    MANIFEST_FILES="${MANIFEST_PATH}"
                    echo "Using single manifest file: ${MANIFEST_FILES}"
                elif [ -d "${MANIFEST_PATH}" ]; then
                    echo "Manifest path is a directory. Searching for kustomization.yaml files..."
                    MANIFEST_FILES=$(find "${MANIFEST_PATH}" -type f -name "kustomization.yaml" | tr '\\n' ' ')
                else
                    echo "Manifest path ${MANIFEST_PATH} not found as file or directory."
                    echo "Searching for kustomization.yaml files in overlay* directories..."
                    MANIFEST_FILES=$(find . -type f -path "*/overlay*/kustomization.yaml" | head -n 1 | tr '\\n' ' ')
                    
                    if [ -z "${MANIFEST_FILES}" ]; then
                        echo "Searching for any kustomization.yaml files..."
                        MANIFEST_FILES=$(find . -type f -name "kustomization.yaml" | head -n 1 | tr '\\n' ' ')
                    fi
                fi
                
                if [ -z "${MANIFEST_FILES}" ]; then
                    echo "ERROR: No kustomization.yaml files found."
                    exit 1
                fi
                
                echo "Found manifest files: ${MANIFEST_FILES}"

                UPDATED_FILES=""
                
                for MANIFEST_FILE in ${MANIFEST_FILES}; do
                    if [ ! -f "${MANIFEST_FILE}" ]; then
                        echo "WARNING: File not found: ${MANIFEST_FILE}"
                        continue
                    fi

                    cp "${MANIFEST_FILE}" "${MANIFEST_FILE}.backup"
                    
                    case "${MANIFEST_FILE}" in
                        *.yaml|*.yml)
                            echo "Detected YAML file - applying kustomization tag update..."
                            echo "Before update:"
                            grep -A 3 "images:" "${MANIFEST_FILE}" || true
                            
                            # Update only newTag field in kustomization.yaml (preserve indentation and structure)
                            sed -i -E "s|^([[:space:]]*newTag:)[[:space:]].*$|\\1 \\"${NEW_IMAGE_TAG}\\"|g" "${MANIFEST_FILE}"
                            
                            echo "After update:"
                            grep -A 3 "images:" "${MANIFEST_FILE}" || true
                            ;;
                        *)
                            echo "Unknown file type, applying generic kustomization pattern..."
                            sed -i -E "s|^([[:space:]]*newTag:)[[:space:]].*$|\\1 \\"${NEW_IMAGE_TAG}\\"|g" "${MANIFEST_FILE}" || true
                            ;;
                    esac
                    

                    if ! git diff --quiet "${MANIFEST_FILE}"; then
                        UPDATED_FILES="${UPDATED_FILES} ${MANIFEST_FILE}"
                        echo "[SUCCESS] Updated image in ${MANIFEST_FILE}"
                        echo ""
                        echo "Changes:"
                        git diff "${MANIFEST_FILE}" || true
                        rm -f "${MANIFEST_FILE}.backup"
                    else
                        echo "[INFO] No changes detected in ${MANIFEST_FILE}"

                        mv "${MANIFEST_FILE}.backup" "${MANIFEST_FILE}"
                    fi
                done
                
                
                if [ -z "${UPDATED_FILES}" ]; then
                    echo "WARNING: No files were updated."
                    
                    # Check if kustomization newTag field exists
                    if grep -q "newTag:" ${MANIFEST_FILES} 2>/dev/null; then
                        echo "Kustomization newTag field found in manifests but no updates made."
                        echo "This might mean the tag already matches: ${NEW_IMAGE_TAG}"
                    else
                        echo "ERROR: No newTag field found in kustomization files."
                        exit 1
                    fi
                else
                    echo "Successfully updated files:"
                    for file in ${UPDATED_FILES}; do
                        echo "  - ${file}"
                    done
                fi
        '''
    }
    
    echo "=== Checking for changes to commit ==="
    def hasChanges = sh(
        script: '''
                git add -u *.yaml *.yml 2>/dev/null || true
                
                find . -type f \\( -name "*.yaml" -o -name "*.yml" \\) -exec git add {} \\; 2>/dev/null || true
                
                if git diff --staged --quiet; then
                    echo "false"
                else
                    echo "true"
                fi
        ''',
        returnStdout: true
    ).trim() == "true"
    
    if (hasChanges) {
        echo "[SUCCESS] Changes detected. Committing and pushing..."
        
        def commitMsg = appRepoCommitId ? 
            "Update image to ${newImageName}:${newImageTag}\n\nSource commit: ${appRepoCommitId}" :
            "Update image to ${newImageName}:${newImageTag}"
        
        sh """
            git commit -m "${commitMsg}"
            echo "[SUCCESS] Changes committed"
        """
        
        withCredentials([usernamePassword(
            credentialsId: "${gitCredId}",
            usernameVariable: 'GIT_USERNAME',
            passwordVariable: 'GIT_PASSWORD'
        )]) {
            sh """
                set -e
                
                ORIGINAL_URL=\$(git remote get-url origin)
                
                REPO_PATH=\$(echo "${manifestRepo}" | sed 's|https\\?://||' | sed 's|.*@||')
                
                git remote set-url origin "https://\${GIT_USERNAME}:\${GIT_PASSWORD}@\${REPO_PATH}"
                
                echo "Pushing directly to branch: ${manifestBranch}"
                git push origin HEAD:${manifestBranch}
                echo "[SUCCESS] Changes pushed successfully to ${manifestBranch}"
                
                # Restore original remote URL
                git remote set-url origin "\${ORIGINAL_URL}"
            """
        }
        
        echo "=== Manifest Update Process Completed Successfully ==="
        echo "Changes have been committed and pushed directly to: ${manifestBranch}"
            
    } else {
        echo "[INFO] No changes to commit."
        echo "Image might already be set to ${newImageName}:${newImageTag}"
        echo "=== Process completed with no changes ==="
    }
}