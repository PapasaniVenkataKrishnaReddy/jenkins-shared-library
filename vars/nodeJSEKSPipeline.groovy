def call(Map configMap){
    pipeline {
        agent {
            node {
                label 'roboshop' 
            } 
        }
        environment {
            appVersion = ""
            acc_id = "220719767845"
            region = "us-east-1"
            project = configMap.get("project")
            component = configMap.get("component")
        }
        options {
            timeout(time: 15, unit: 'MINUTES')
        }
        parameters {
            booleanParam(name: 'DEPLOY', defaultValue: false, description: 'Toggle this value')
        }
        stages {
            stage('Read version'){
                steps {
                    script {
                        // Load and parse the JSON file
                        def packageJson = readJSON file: 'package.json'
                        
                        // Access fields directly
                        appVersion = packageJson.version
                        echo "Building version ${appVersion}"
                        //sh 'printenv | sort'
                    }
                }
            }
            stage('Install Dependencies') {
                steps {
                    script{
                        sh """
                            npm install
                        """
                    }
                }
            }
            stage('Unit tests') {
                steps {
                    script{
                        def testResult = sh(script: 'npm test', returnStatus: true)
                        if (testResult != 0) {
                            utils.updateCommitStatus('failure', 'Unit tests failed', 'unit-tests')
                            error "Unit tests failed."
                        } else {
                            utils.updateCommitStatus('success', 'Unit tests passed', 'unit-tests')
                        }
                    }
                }
            }
            stage ('SonarQube Analysis'){
                steps {
                    script {
                        def scannerHome = tool name: 'sonar-8' // agent configuration
                        withSonarQubeEnv('sonar-server') { // analysing and uploading to server
                            sh "${scannerHome}/bin/sonar-scanner"
                        }
                    }
                }
            }
            stage("Quality Gate") {
                steps {
                    script {
                        timeout(time: 1, unit: 'HOURS') {
                            def qg = waitForQualityGate()
                            if (qg.status != 'OK') {
                                utils.updateCommitStatus('failure', "SonarQube quality gate failed: ${qg.status}", 'sonar-scan')
                                error "Quality gate failed: ${qg.status}"
                            } else {
                                utils.updateCommitStatus('success', 'SonarQube quality gate passed', 'sonar-scan')
                            }
                        }
                    }
                }
            }
            stage('Check Dependabot Alerts') {
                steps {
                    withCredentials([string(credentialsId: 'github-token', variable: 'GH_TOKEN')]) {
                        sh '''
                            set -e

                            REPO="krishna-33s/catalogue-unit-test"

                            curl -s -L \
                            -H "Accept: application/vnd.github+json" \
                            -H "Authorization: Bearer ${GH_TOKEN}" \
                            -H "X-GitHub-Api-Version: 2026-03-10" \
                            "https://api.github.com/repos/${REPO}/dependabot/alerts?state=open" \
                            -o alerts.json

                            echo "---- Open Dependabot Alerts ----"
                            jq -r '.[] | "\\(.number)\\t\\(.security_vulnerability.severity)\\t\\(.dependency.package.name)\\t\\(.security_advisory.ghsa_id)"' alerts.json

                            HIGH_CRITICAL_COUNT=$(jq '[.[] | select(.security_vulnerability.severity == "high" or .security_vulnerability.severity == "critical")] | length' alerts.json)

                            echo "High/Critical alert count: ${HIGH_CRITICAL_COUNT}"

                            if [ "$HIGH_CRITICAL_COUNT" -gt 0 ]; then
                                echo " Found ${HIGH_CRITICAL_COUNT} High/Critical severity dependency alert(s). Failing build."
                                exit 1
                            else
                                echo " No High/Critical dependency alerts found."
                            fi
                        '''
                    }
                }
            }
            stage('Build Image') {
                steps {
                script{
                        withAWS(credentials: 'aws-creds', region: "${region}") {
                            // Commands here have AWS authentication
                            sh """
                                aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com
                                docker build -t ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion} .
                            """
                        }
                    }
                }
            }
            stage('Trivy OS Scan') {
                steps {
                    script {
                        // Generate table report
                        sh """
                            trivy image \
                                --scanners vuln \
                                --pkg-types os \
                                --severity HIGH,MEDIUM \
                                --format table \
                                --output trivy-os-report.txt \
                                --exit-code 0 \
                                ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion}
                        """

                        // Print table to console
                        sh 'cat trivy-os-report.txt'

                        // Fail pipeline if vulnerabilities found
                        def scanResult = sh(
                            script: """
                                trivy image \
                                    --scanners vuln \
                                    --pkg-types os \
                                    --severity HIGH,MEDIUM \
                                    --format table \
                                    --exit-code 1 \
                                    --quiet \
                                    ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion}
                            """,
                            returnStatus: true
                        )

                        if (scanResult != 0) {
                            utils.updateCommitStatus('failure', 'Trivy OS scan: HIGH/MEDIUM vulnerabilities found', 'trivy-scan')
                            error "🚨 Trivy found HIGH/MEDIUM OS vulnerabilities. Pipeline failed."
                        } else {
                            utils.updateCommitStatus('success', 'Trivy OS scan passed — no HIGH/MEDIUM vulnerabilities', 'trivy-scan')
                            echo "✅ No HIGH or MEDIUM OS vulnerabilities found. Pipeline continues."
                        }
                    }
                }
            }
            // stage('Trivy Dockerfile Scan'){
            //     steps {
            //         script {
            //             sh """
            //                 trivy config \
            //                     --severity HIGH,MEDIUM \
            //                     --format table \
            //                     --output trivy-dockerfile-report.txt \
            //                     Dockerfile
            //             """

            //             sh 'cat trivy-dockerfile-report.txt'

            //             def scanResult = sh(
            //                 script: """
            //                     trivy config \
            //                         --severity HIGH,MEDIUM \
            //                         --exit-code 1 \
            //                         --format table \
            //                         Dockerfile
            //                 """,
            //                 returnStatus: true
            //             )

            //             if (scanResult != 0) {
            //                 error "🚨 Trivy found HIGH/MEDIUM misconfigurations in Dockerfile. Pipeline failed."
            //             } else {
            //                 echo "✅ No HIGH or MEDIUM Dockerfile misconfigurations found. Pipeline continues."
            //             }
            //         }
            //     }
            // }
            stage ('Push image to ECR'){
                steps {
                    script {
                        withAWS(credentials: 'aws-creds', region: "${region}") {
                            sh """
                                aws ecr get-login-password --region ${region} | docker login --username AWS --password-stdin ${acc_id}.dkr.ecr.us-east-1.amazonaws.com
                                docker push ${acc_id}.dkr.ecr.${region}.amazonaws.com/${project}/${component}:${appVersion}
                            """
                        }
                    }
                }
            }
        }

    // post build
        post { 
            always { 
                echo 'I will always say Hello again!'
                cleanWs()
            }
            success {
                echo "pipeline success"
            }
            failure {
                echo "pipeline failure"
            }
        }
    }
}

