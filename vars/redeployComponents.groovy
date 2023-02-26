/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the implementation of the redeploy component pipeline.
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.deployToEnv = args.redeployEnv
    projectInfo.ENV_TO = projectInfo.deployToEnv.toUpperCase()
    projectInfo.deployToNamespace = projectInfo.nonProdNamespaces[projectInfo.deployToEnv]

    stage('Checkout all component repositories') {
        loggingUtils.echoBanner("CLONE ALL MICROSERVICE REPOSITORIES IN PROJECT")

        def jsonPath = '{range .items[?(@.data.src-commit-hash)]}{.data.component}{":"}{.data.deployment-branch}{" "}'
        def script = "oc get cm -l projectid=${projectInfo.id} -o jsonpath='${jsonPath}' -n ${projectInfo.deployToNamespace}"
        def msNameDepBranch = sh(returnStdout: true, script: script).split(' ')
        
        def branchPrefix = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${projectInfo.deployToEnv}-"
        def msToBranch = msNameDepBranch.find { it.startsWith("${component.name}:${branchPrefix}") }

        def deployedMarker = '<DEPLOYED>'
        concurrentUtils.runCloneGitReposStages(projectInfo, projectInfo.components) { component ->
            component.deploymentBranch = msToBranch ? msToBranch.split(':')[1] : ''
            component.deploymentImageTag = component.deploymentBranch.replaceAll("${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-", '')

            dir(component.workDir) {
                branchPrefix = "refs/remotes/**/${branchPrefix}*"
                def branchesAndTimesScript =
                    "git for-each-ref --count=5 --format='%(refname:short) (%(committerdate))' --sort='-committerdate' '${branchPrefix}'"
                def branchesAndTimes = sh(returnStdout: true, script: branchesAndTimesScript).trim()
                branchesAndTimes = branchesAndTimes.replaceAll("origin/${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-", '')

                def deployLine
                branchesAndTimes.split('\n').each { line ->
                    deployLine = !deployLine && line.startsWith(component.deploymentImageTag) ? line : deployLine
                }
                component.deployBranchesAndTimes = deployLine ? branchesAndTimes.replace(deployLine, "${deployLine} ${deployedMarker}") : branchesAndTimes
            }
        }
    }

    stage ('Select components and environment to redeploy to or remove from') {
        loggingUtils.echoBanner("SELECT WHICH COMPONENTS TO REDEPLOY OR REMOVE")

        def inputs = []
        projectInfo.components.each { component ->
            inputs += choice(name: component.name,
                             description: "status: ${component.status}",
                             choices: "${el.cicd.IGNORE}\n${component.deployBranchesAndTimes}\n${el.cicd.REMOVE}")
        }

        def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select components to redeploy in ${projectInfo.deployToEnv}", inputs)

        def willRedeployOrRemove = false
        projectInfo.componentsToRedeploy = projectInfo.components.findAll { component ->
            def answer = (inputs.size() > 1) ? cicdInfo[component.name] : cicdInfo
            component.remove = (answer == el.cicd.REMOVE)
            component.redeploy = (answer != el.cicd.IGNORE && answer != el.cicd.REMOVE)
            

            if (component.redeploy) {
                component.deploymentImageTag = (answer =~ "${projectInfo.deployToEnv}-[0-9a-z]{7}")[0]
                component.srcCommitHash = component.deploymentImageTag.split('-')[1]
                component.deploymentBranch = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${component.deploymentImageTag}"
            }

            willRedeployOrRemove = willRedeployOrRemove || answer
            return component.redeploy
        }

        if (!willRedeployOrRemove) {
            loggingUtils.errorBanner("NO COMPONENTS SELECTED FOR REDEPLOYMENT OR REMOVAL FOR ${projectInfo.deployToEnv}")
        }
    }

    stage('Verify image(s) exist for environment') {
        loggingUtils.echoBanner("VERIFY IMAGE(S) TO REDEPLOY EXIST IN IMAGE REPOSITORY:",
                                 projectInfo.componentsToRedeploy.collect { "${it.id}:${it.deploymentImageTag}" }.join(', '))

        def allImagesExist = true
        def errorMsgs = ["MISSING IMAGE(s) IN ${projectInfo.deployToNamespace} TO REDEPLOY:"]
        withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployToEnv),
                                          usernameVariable: 'TO_IMAGE_REGISTRY_USERNAME',
                                          passwordVariable: 'TO_IMAGE_REGISTRY_PWD')])
        {
            def imageRepoUserNamePwd = el.cicd["${projectInfo.ENV_TO}${el.cicd.IMAGE_REGISTRY_USERNAME_POSTFIX}"] + ":\${TO_IMAGE_REGISTRY_PULL_TOKEN}"
            projectInfo.componentsToRedeploy.each { component ->
                def verifyImageCmd =
                    shCmd.verifyImage(projectInfo.ENV_TO,
                                      'TO_IMAGE_REGISTRY_USERNAME',
                                      'TO_IMAGE_REGISTRY_PWD',
                                      component.id,
                                      component.deploymentImageTag)
                                      
                echo verifyImageCmd

                if (!sh(returnStdout: true, script: verifyImageCmd).trim()) {
                    errorMsgs << "    ${component.id}:${projectInfo.deploymentImageTag} NOT FOUND IN ${projectInfo.deployToEnv} (${projectInfo.deployToNamespace})"
                }
            }
        }

        if (errorMsgs.size() > 1) {
            loggingUtils.errorBanner(errorMsgs)
        }
    }

    stage('Checkout all deployment branches') {
        loggingUtils.echoBanner("CHECKOUT ALL DEPLOYMENT BRANCHES")

        projectInfo.componentsToRedeploy.each { component ->
            dir(component.workDir) {
                sh "git checkout ${component.deploymentBranch}"
                component.deploymentCommitHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
            }
        }
    }

    stage('tag images to redeploy for environment') {
        loggingUtils.echoBanner("TAG IMAGES FOR REPLOYMENT IN ENVIRONMENT TO ${projectInfo.deployToEnv}:",
                                 projectInfo.componentsToRedeploy.collect { "${it.id}:${it.deploymentImageTag}" }.join(', '))

        withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployToEnv),
                                          usernameVariable: 'TO_IMAGE_REGISTRY_USERNAME',
                                          passwordVariable: 'TO_IMAGE_REGISTRY_PWD')])
        {
            def imageRepoUserNamePwd = el.cicd["${projectInfo.ENV_TO}${el.cicd.IMAGE_REGISTRY_USERNAME_POSTFIX}"] + ":\${TO_IMAGE_REGISTRY_PULL_TOKEN}"
            projectInfo.componentsToRedeploy.each { component ->
                def tagImageCmd =
                    shCmd.tagImage(projectInfo.ENV_TO,
                                   'TO_IMAGE_REGISTRY_USERNAME',
                                   'TO_IMAGE_REGISTRY_PWD',
                                   component.id,
                                   component.deploymentImageTag,
                                   projectInfo.deployToEnv)
                sh """
                    ${shCmd.echo '', "--> Tagging image '${component.id}:${component.deploymentImageTag}' as '${component.id}:${projectInfo.deployToEnv}'"}

                    ${tagImageCmd}
                """
            }
        }
    }

    deployComponents(projectInfo: projectInfo,
                     components: projectInfo.componentsToRedeploy,
                     componentsToRemove: projectInfo.components.findAll { it.remove },
                     imageTag: projectInfo.deployToEnv)
}
