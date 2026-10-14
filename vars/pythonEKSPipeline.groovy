def call(Map configMap) {
    def appVersion = ''

    pipeline {
        agent {
            label 'AGENT-CI'
        }

        environment {
            COURSE = 'Jenkins'
            ACC_ID = '220719767845'
            PROJECT = "${configMap.get('project')}"
            COMPONENT = "${configMap.get('component')}"
        }

        options {
            timeout(time: 10, unit: 'MINUTES')
            disableConcurrentBuilds()
        }

        stages {
            stage('Read Version') {
                steps {
                    script {
                        appVersion = readFile(file: 'version').trim()
                        echo "app version: ${appVersion}"
                    }
                }
            }

            stage('Install Dependencies') {
                steps {
                    sh 'python3 -m pip install -r requirements.txt'
                }
            }

            stage('Unit Tests') {
                steps {
                    sh 'echo "unit tests performed.."'
                }
            }

            // stage('Sonar Scan') {
            //     environment {
            //         SCANNER_HOME = tool 'sonar-8.0'
            //     }
            //     steps {
            //         withSonarQubeEnv('sonar-server') {
            //             sh "${SCANNER_HOME}/bin/sonar-scanner"
            //         }
            //     }
            // }
            //
            // stage('Quality Gate') {
            //     steps {
            //         timeout(time: 1, unit: 'HOURS') {
            //             waitForQualityGate abortPipeline: true
            //         }
            //     }
            // }

            stage('Check CI Tools') {
                steps {
                    sh '''
                        set -e
                        export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:${PATH}"
                        echo "Jenkins user: $(whoami)"
                        echo "PATH=${PATH}"
                        command -v aws
                        command -v python3
                        CONTAINER_CLI="$(command -v docker || command -v podman || true)"
                        if [ -z "${CONTAINER_CLI}" ]; then
                            echo "Neither docker nor podman is available in the Jenkins agent session"
                            ls -l /usr/bin/docker /usr/local/bin/docker /usr/bin/podman /usr/local/bin/podman 2>/dev/null || true
                            exit 1
                        fi
                        echo "Container CLI: ${CONTAINER_CLI}"
                        python3 --version
                        "${CONTAINER_CLI}" --version
                        "${CONTAINER_CLI}" ps
                    '''
                }
            }

            stage('Build Image') {
                steps {
                    script {
                        if (!appVersion) {
                            error 'Application version was not detected before image build'
                        }
                        def image = "${env.ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${env.PROJECT}/${env.COMPONENT}:${appVersion}"

                        withAWS(region: 'us-east-1', credentials: 'aws-creds') {
                            sh """
                                export PATH="/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin:\${PATH}"
                                CONTAINER_CLI="\$(command -v docker || command -v podman)"
                                aws ecr get-login-password --region us-east-1 | "\${CONTAINER_CLI}" login --username AWS --password-stdin ${env.ACC_ID}.dkr.ecr.us-east-1.amazonaws.com
                                "\${CONTAINER_CLI}" build -t ${image} .
                                "\${CONTAINER_CLI}" images
                                "\${CONTAINER_CLI}" push ${image}
                            """
                        }
                    }
                }
            }

            // stage('Trivy Scan') {
            //     steps {
            //         script {
            //             sh """
            //                 trivy image \
            //                 --scanners vuln \
            //                 --severity HIGH,CRITICAL,MEDIUM \
            //                 --pkg-types os \
            //                 --exit-code 1 \
            //                 --format table \
            //                 ${env.ACC_ID}.dkr.ecr.us-east-1.amazonaws.com/${env.PROJECT}/${env.COMPONENT}:${env.APP_VERSION}
            //             """
            //         }
            //     }
            // }

            stage('Trigger Deploy') {
                steps {
                    script {
                        def deployTo = 'dev'
                        def deployJob = "../${env.COMPONENT}-CD/${deployTo}"
                        echo "Triggering deploy job: ${deployJob}"

                        build job: deployJob,
                              wait: false,
                              propagate: false,
                              parameters: [
                                  string(name: 'appVersion', value: appVersion),
                                  string(name: 'deploy_to', value: deployTo)
                              ]
                    }
                }
            }
        }

        post {
            always {
                echo 'cleaning workspace...'
                cleanWs()
            }
            success {
                echo 'I will run if success'
            }
            failure {
                echo 'I will run if failure'
            }
            aborted {
                echo 'pipeline is aborted'
            }
        }
    }
}