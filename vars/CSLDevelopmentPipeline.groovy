def call(body) {
    // evaluate the body block, and collect configuration into the object
    def pipelineParams = [:]
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body.delegate = pipelineParams
    body()

    // Define the pipeline to be executed
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
            stage('checkout') {
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
                }
            }








            stage('build') {
                environment { 
                    CSL_DOCKER_IMAGE_NAME = "${env.CSL_REGISTRY}${env.CSL_REPO_NAME}:${env.CSL_VERSION}${env.CSL_BUILD}"
                }
                steps {
/*                    
                    agent {
                        dockerfile true
                        reuseNode true  // Run the container on the same node as everything else
                    }
                    println "Build to tag image with = " + "${env.CSL_BUILD}"
*/
//                    app = docker.build("${CSL_DOCKER_IMAGE_NAME}")

                    script { 
                        env.CSL_APP = docker.build("${CSL_DOCKER_IMAGE_NAME}")
                    }
                }
            }

            stage ('publish for development') {
                steps {
                    withAWS(region:'us-west-1', credentials:'AWS_DOCKER_REPO') {
                        script { 

                            // Get the Docker login command to execute.
                            def login = ecrLogin()

                            // Login to the AWS account that will push the images
                            sh login

                            docker.withRegistry(registry) {

                                // Push the current version as the lastest version
                                env.CSL_APP.push('latest')

                                // Push the current version and reset the version as the previous line changed it
                                app.push(version + build)
                            }
                        }
                    }
                }
            }

            stage ('test') {
                steps {
                    script { 
                        try {
                            // Remove any chance that the container could be left from previous attempts to test
                            try {sh 'docker stop ' + appName} catch (ex) {/* ignore */}
                            try {sh 'docker rm ' + appName} catch (ex) {/* ignore */}

                            // Run the container to ensure it works
                            container = app.run('--name ' + appName)

                            /************************************************************************************
                            Call the specific testing mechanism defined by the repo being built
                            ************************************************************************************/
                            cslTest()

                            // Get the logs of the container to show in the jenkins log as this will contain the text to prove that
                            // the build job was successful
                            sh 'docker logs ' + appName
                        }
                        finally {
                            try { container.stop } catch (ex) { /* ignore */ }
                        }
                    }
                }
            }

            stage('deploy to development server'){
                steps {
                    script {
//                    deploy(pipelineParams.developmentServer, pipelineParams.serverPort)


                        /*  !!!!!!!!!!!!!!!!!!!!!!!!!!!!!! NOTE !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                            The docker command to deploy the stack is executed on the hosts docker daemon. When using AWS registry the AWS command
                            also needs to be executed on the host and hence to deploy the static we use ssh to execute the commands
                        */
                        def sshCommand = '(aws ecr get-login --no-include-email --region us-west-1) | source /dev/stdin && ' +
                            'docker stack deploy --compose-file ./compose-files/' + composeFilename + " " + stackName

                        // Deploy the stack in the existing swarm
                        /*
                        sshPublisher(publishers: [sshPublisherDesc(configName: 'Development', 
                            transfers: [sshTransfer(cleanRemote: false, excludes: '', execCommand: sshCommand, execTimeout: 120000, 
                            flatten: false, makeEmptyDirs: false, noDefaultExcludes: false, patternSeparator: '[, ]+', 
                            remoteDirectory: '', remoteDirectorySDF: false, removePrefix: '', sourceFiles: '')], 
                            usePromotionTimestamp: false, useWorkspaceInPromotion: false, verbose: false)])
                        */
                        sshPublisher(publishers: [sshPublisherDesc(configName: 'Development', 
                            transfers: [sshTransfer(cleanRemote: false, excludes: '', execCommand: sshCommand, execTimeout: 120000, 
                            flatten: false, makeEmptyDirs: true, noDefaultExcludes: false, patternSeparator: '[, ]+', 
                            remoteDirectory: 'compose-files', remoteDirectorySDF: false, removePrefix: '', sourceFiles: composeFilename)], 
                            usePromotionTimestamp: false, useWorkspaceInPromotion: false, verbose: false)])
                    }
                }
            }
        }
        post {
            failure {
                mail to: pipelineParams.email, subject: 'Pipeline failed', body: "${env.BUILD_URL}"
            }
        }
    }
}


