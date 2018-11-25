sdef call(body) {
    // evaluate the body block, and collect configuration into the object
    def config = [:]
//    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = config
    body()




    pipeline {

        // Only run the pipeline on nodes with the 'docker' label
        agent {
            node {
                label 'docker'
            }
        }

        // Set up environment variables to be used in the pipeline
        environment { 
            CSL_MESSAGE = 'hi jon'
            CSL_REGISTRY = 'https://index.docker.io/v1/'
            CSL_COMPOSE_FILENAME = 'csl-test-jenkins-compose.yml'
            CSL_REGISTRY_CREDENTIALS = credentials('demo-dockerhub-credentials')
        }

        stages {
            stage('checkout scm') {
                steps {
//                    git branch: pipelineParams.branch, credentialsId: 'GitCredentials', url: pipelineParams.scmUrl
                    checkout scm

                    script { 
                        // Get the name of the repo from the scm
                        env.CSL_REPO_NAME = scm.getUserRemoteConfigs()[0].getUrl().tokenize('/').last().split("\\.")[0]
                        // Use the time in ms as the build number
                        env.CSL_BUILD = ".build-" + System.currentTimeMillis()

                        if (!env.CSL_VERSION) {
                            // Get the latest version tag from the repo. Version tags are in the format v1.0.1, 
                            // a v at the beginning of the line followed by digits '.' digits '.' digits
                            def version = sh(script: "git tag | sed -n -e 's/^v\\([0-9]*\\.[0-9]*\\.[0-9]*\\)/\\1/p' | tail -1", returnStdout: true)

                            // Remove any rubbish charactes from the version
                            env.CSL_VERSION = version.replaceAll("\\s","")
                        }

                        // Get the name of the user who started this build
                        wrap([$class: 'BuildUser']) { 
                            env.CSL_STACK_NAME = "${env.BUILD_USER_ID}"
                        }
                    }

                    sh 'printenv'

                }
            }

            stage('build') {
                steps {
//                    sh 'mvn clean package -DskipTests=true'
                    sh 'echo "Hi Jon this is the test shared library build step"'
                }
            }
/*
            stage ('test') {
                steps {
                    parallel (
                        "unit tests": { sh 'mvn test' },
                        "integration tests": { sh 'mvn integration-test' }
                    )
                }
            }

            stage('deploy developmentServer'){
                steps {
                    deploy(pipelineParams.developmentServer, pipelineParams.serverPort)
                }
            }

            stage('deploy staging'){
                steps {
                    deploy(pipelineParams.stagingServer, pipelineParams.serverPort)
                }
            }

            stage('deploy production'){
                steps {
                    deploy(pipelineParams.productionServer, pipelineParams.serverPort)
                }
            }
*/
        }
        post {
            failure {
                mail to: pipelineParams.email, subject: 'Pipeline failed', body: "${env.BUILD_URL}"
            }
        }
    }
}