#!/usr/bin/env groovy

def call(String manifestRepo, String manifestBranch, String gitCredId, String manifestPath, 
         String newImageName, String newImageTag, String appRepoCommitId = '', 
         String prTitle = '', String prBody = '', String ghTokenCredId = '') {
    
    script {
        echo "=== Starting Manifest Update Process ==="
        echo "Repository: ${manifestRepo}"
        echo "Branch: ${manifestBranch}"
        echo "New Image: ${newImageName}:${newImageTag}"
        echo "App Repo Commit ID: ${appRepoCommitId ?: 'Not provided'}"
        echo "Manifest Path: ${manifestPath}"
        echo "GitHub Token Provided: ${ghTokenCredId ? 'Yes' : 'No (PR will not be created)'}"
        
        def shortCommit = appRepoCommitId ? appRepoCommitId.substring(0, Math.min(8, appRepoCommitId.length())) : ""
        def commitInfo = shortCommit ? " (commit: ${shortCommit})" : ""
        def prTitleValue = prTitle ?: "Update image to ${newImageName}:${newImageTag}${commitInfo}"
        
        def prBodyText = "Automated update of image to ${newImageName}:${newImageTag}"
        if (appRepoCommitId) {
            prBodyText += "\n\nSource Commit: ${appRepoCommitId}"
        }
        prBodyText += "\nJenkins Build: #${BUILD_NUMBER}"
        def prBodyValue = prBody ?: prBodyText
        
        def sanitizedImageName = newImageName.replaceAll('[^a-zA-Z0-9]', '-').replaceAll('-+', '-').toLowerCase()
        def sanitizedTag = newImageTag.replaceAll('[^a-zA-Z0-9]', '-').replaceAll('-+', '-')
        def gitBranchName = "jenkins-update-image-${sanitizedTag}"
        
        echo "Creating branch: ${gitBranchName}"
        
        if (isUnix()) {
            echo "=== Checking GitHub CLI installation ==="
            sh '''
                if ! command -v gh > /dev/null; then
                    echo "GitHub CLI not found. Installing GitHub CLI..."
                    
                        echo "Detected Linux OS. Installing via apt..."
                        curl -fsSL https://cli.github.com/packages/githubcli-archive-keyring.gpg | sudo dd of=/usr/share/keyrings/githubcli-archive-keyring.gpg
                        sudo chmod go+r /usr/share/keyrings/githubcli-archive-keyring.gpg
                        echo "deb [arch=$(dpkg --print-architecture) signed-by=/usr/share/keyrings/githubcli-archive-keyring.gpg] https://cli.github.com/packages stable main" | sudo tee /etc/apt/sources.list.d/github-cli.list > /dev/null
                        sudo apt update
                        sudo apt install -y gh
                    
                    echo "GitHub CLI installed successfully."
                else
                    echo "GitHub CLI is already installed."
                    gh --version
                fi
            '''
        }
          
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
            "MANIFEST_PATH=${manifestPath}",
            "GIT_BRANCH_NAME=${gitBranchName}"
        ]) {
            sh '''
                set -e
                
                # Configure Git
                git config user.name "Jenkins-CI-Bot"
                git config user.email "jenkins@opqtech.com"
                
                NEW_IMAGE="${NEW_IMAGE_NAME}:${NEW_IMAGE_TAG}"
                
                echo "Target image: ${NEW_IMAGE}"
                echo "Manifest path: ${MANIFEST_PATH}"
                
                git checkout -b "${GIT_BRANCH_NAME}"
                echo "Created and checked out branch: ${GIT_BRANCH_NAME}"
                 
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
                    
                    echo "Pushing to branch: ${gitBranchName}"
                    git push origin ${gitBranchName}
                    echo "[SUCCESS] Changes pushed successfully"
                    
                    # Restore original remote URL
                    git remote set-url origin "\${ORIGINAL_URL}"
                """
            }
            
            if (ghTokenCredId) {
                echo "=== Creating Pull Request ==="
                
                withCredentials([string(credentialsId: "${ghTokenCredId}", variable: 'GH_TOKEN')]) {
                    sh """
                        set -e
                        
                        echo "Authenticating with GitHub..."
                        echo "[INFO] Using GH_TOKEN environment variable for authentication"
                        
                        if gh auth status 2>&1 | grep -q "Logged in"; then
                            echo "[SUCCESS] GitHub CLI authenticated successfully"
                        else
                            echo "[INFO] GitHub CLI will use GH_TOKEN from environment"
                        fi
                        
                        REPO_URL=\$(echo "${manifestRepo}" | sed 's|.*github.com[:/]||' | sed 's|\\.git\$||')
                        echo "Repository: \${REPO_URL}"
                        echo "Base branch: ${manifestBranch}"
                        echo "Head branch: ${gitBranchName}"
                        echo "PR Title: ${prTitleValue}"
                        
                        echo "Verifying remote branch exists..."
                        if git ls-remote --heads origin "${gitBranchName}" | grep -q "${gitBranchName}"; then
                            echo "[SUCCESS] Branch ${gitBranchName} exists on remote"
                        else
                            echo "[ERROR] Branch ${gitBranchName} not found on remote"
                            echo "Available branches:"
                            git ls-remote --heads origin | head -10
                            exit 1
                        fi
                        
                        echo "Verifying base branch exists..."
                        if git ls-remote --heads origin "${manifestBranch}" | grep -q "${manifestBranch}"; then
                            echo "[SUCCESS] Base branch ${manifestBranch} exists"
                        else
                            echo "[ERROR] Base branch ${manifestBranch} not found on remote"
                            echo "Available branches:"
                            git ls-remote --heads origin | head -10
                            exit 1
                        fi
                        
                        set +e
                        gh pr create \\
                            --repo "\${REPO_URL}" \\
                            --title "${prTitleValue}" \\
                            --body "${prBodyValue}" \\
                            --base "${manifestBranch}" \\
                            --head "${gitBranchName}" 2>&1
                        PR_CREATE_EXIT_CODE=\$?
                        set -e
                        
                        if [ \${PR_CREATE_EXIT_CODE} -eq 0 ]; then
                            echo ""
                            echo "[SUCCESS] Pull request created successfully!"
                        else
                            echo ""
                            echo "[WARNING] PR creation failed with exit code: \${PR_CREATE_EXIT_CODE}"
                            echo "Checking if PR already exists..."
                            
                            set +e
                            EXISTING_PR=\$(gh pr list --repo "\${REPO_URL}" --head "${gitBranchName}" --json number --jq '.[0].number' 2>/dev/null)
                            LIST_EXIT_CODE=\$?
                            set -e
                            
                            if [ \${LIST_EXIT_CODE} -eq 0 ] && [ -n "\${EXISTING_PR}" ] && [ "\${EXISTING_PR}" != "null" ]; then
                                echo "[INFO] PR #\${EXISTING_PR} already exists for branch ${gitBranchName}"
                                echo "PR URL: https://github.com/\${REPO_URL}/pull/\${EXISTING_PR}"
                                echo "[SUCCESS] Using existing pull request"
                            else
                                echo ""
                                echo "=========================================="
                                echo "ERROR: Failed to create PR"
                                echo "=========================================="
                                echo "Exit code: \${PR_CREATE_EXIT_CODE}"
                                echo ""
                                echo "Possible reasons:"
                                echo "  1. A PR already exists but couldn't be detected"
                                echo "  2. Insufficient GitHub token permissions (needs 'repo' scope)"
                                echo "  3. Repository settings may prevent PR creation"
                                echo "  4. Branch protection rules may be blocking"
                                echo ""
                                echo "Debug information:"
                                echo "  Repository: \${REPO_URL}"
                                echo "  Base branch: ${manifestBranch}"
                                echo "  Head branch: ${gitBranchName}"
                                echo ""
                                echo "You can manually create the PR at:"
                                echo "  https://github.com/\${REPO_URL}/compare/${manifestBranch}...${gitBranchName}"
                                exit 1
                            fi
                        fi
                        
                        echo ""
                        echo "Logging out from GitHub CLI..."
                        gh auth logout --hostname github.com 2>/dev/null || true
                        echo "[INFO] PR creation process completed"
                    """
                }
            } else {
                echo "[WARNING] GitHub token not provided. Skipping PR creation."
                echo "Changes have been pushed to branch: ${gitBranchName}"
                echo "Please create the PR manually."
            }
            
            echo "=== Manifest Update Process Completed Successfully ==="
            
        } else {
            echo "[INFO] No changes to commit."
            echo "Image might already be set to ${newImageName}:${newImageTag}"
            echo "=== Process completed with no changes ==="
        }
    }
}