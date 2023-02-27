/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the implementation of the component promotion pipeline.
 */
 
def getUserPromotionRemovalSelections(def projectInfo) {
    def ENV_DELIMITER = ' to '
    def fromEnv = projectInfo.devEnv

    def promotionChoices = []
    projectInfo.testEnvs.findAll { toEnv ->
        promotionChoices << "${fromEnv}${ENV_DELIMITER}${toEnv}"
        fromEnv = toEnv
    }
    promotionChoices << "${fromEnv}${ENV_DELIMITER}${projectInfo.preProdEnv}"

    projectInfo.allowsHotfixes && (promotionChoices << "${el.cicd.hotfixEnv}${ENV_DELIMITER}${el.cicd.preProdEnv}")

    def inputs = [choice(name: 'promotionEnvs', description: '', choices: "${promotionChoices.join('\n')}"),
                  choice(name: 'defaultAction',
                         description: 'Default action to apply to all components',
                         choices: "${el.cicd.IGNORE}\n${el.cicd.PROMOTE}\n${el.cicd.REMOVE}")]

    inputs += projectInfo.components.collect { component ->
        choice(name: component.name,
                description: "status: ${component.status}",
                choices: "${el.cicd.IGNORE}\n${el.cicd.PROMOTE}\n${el.cicd.REMOVE}")
    }

    def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select components to promote and environment to promote from/to:", inputs)

    def promotionEnvs = cicdInfo.promotionEnvs.split(ENV_DELIMITER)
    projectInfo.deployFromEnv = promotionEnvs.first()
    projectInfo.ENV_FROM = projectInfo.deployFromEnv.toUpperCase()
    projectInfo.deployToEnv = promotionEnvs.last()
    projectInfo.ENV_TO = projectInfo.deployToEnv.toUpperCase()
    projectInfo.deployFromNamespace = projectInfo.nonProdNamespaces[projectInfo.deployFromEnv]
    projectInfo.deployToNamespace = projectInfo.nonProdNamespaces[projectInfo.deployToEnv]

    def promoteOrRemove
    projectInfo.components.each { component ->
        def answer = (inputs.size() > 1) ? cicdInfo[component.name] : cicdInfo
        component.promote = answer == el.cicd.PROMOTE || (answer == el.cicd.IGNORE && cicdInfo.defaultAction == el.cicd.PROMOTE)
        component.remove = answer == el.cicd.REMOVE || (answer == el.cicd.IGNORE && cicdInfo.defaultAction == el.cicd.REMOVE)

        promoteOrRemove = promoteOrRemove || component.promote != null
    }

    if (!promoteOrRemove) {
        loggingUtils.errorBanner("NO COMPONENTS SELECTED FOR PROMOTION OR REMOVAL FOR ${projectInfo.deployToEnv}")
    }

    projectInfo.componentsToPromote = projectInfo.components.findAll{ it.promote }
    projectInfo.componentsToRemove = projectInfo.components.findAll{ it.remove }
}

def runVerifyImagesExistStages(def projectInfo, def components) {
    def verifedMsgs = ["IMAGE(s) VERIFED TO EXIST IN THE ${projectInfo.ENV_FROM} IMAGE REPOSITORY:"]
    def errorMsgs = ["MISSING IMAGE(s) IN THE ${projectInfo.ENV_FROM} IMAGE REPOSITORY:"]
    
    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployFromEnv),
                                      usernameVariable: 'IMAGE_REGISTRY_USERNAME',
                                      passwordVariable: 'IMAGE_REGISTRY_PWD')]) {
        def stageTitle = "Verify Image(s) Exist In Registry"
        def verifyImageStages = concurrentUtils.createParallelStages(stageTitle, components) { component ->
            def verifyImageCmd = shCmd.verifyImage(projectInfo.ENV_FROM,
                                                   'IMAGE_REGISTRY_USERNAME',
                                                   'IMAGE_REGISTRY_PWD',
                                                   component.id,
                                                   projectInfo.deployFromEnv)

            if (!sh(returnStdout: true, script: "${verifyImageCmd}").trim()) {
                def image = "${component.id}:${projectInfo.deployFromEnv}"
                errorMsgs << "    ${image} NOT FOUND IN ${projectInfo.deployFromEnv} (${projectInfo.deployFromNamespace})"
            }
            else {
                def imageRepo = el.cicd["${projectInfo.ENV_FROM}${el.cicd.IMAGE_REGISTRY_POSTFIX}"]
                verifedMsgs << "   VERIFIED: ${component.id}:${projectInfo.deployFromEnv} IN ${imageRepo}"
            }
        }

        parallel(verifyImageStages)

        if (verifedMsgs.size() > 1) {
            loggingUtils.echoBanner(verifedMsgs)
        }

        if (errorMsgs.size() > 1) {
            loggingUtils.errorBanner(errorMsgs)
        }
    }
}

def verifyDeploymentsInPreviousEnv(def projectInfo) {
    def jsonPathSingle = '''jsonpath='{.data.component}{":"}{.data.src-commit-hash}{" "}' '''
    def jsonPathMulti = '''jsonpath='{range .items[*]}{.data.component}{":"}{.data.src-commit-hash}{" "}{end}' '''

    def compNames = projectInfo.componentsToPromote.collect { "${it.id}-${el.cicd.META_INFO_POSTFIX}" }
    def jsonPath =  (compNames.size() > 1) ? jsonPathMulti : jsonPathSingle
    def script = "oc get cm --ignore-not-found ${compNames.join(' ')} -o ${jsonPath} -n ${projectInfo.deployFromNamespace}"

    def commitHashMap =  sh(returnStdout: true, script: script).trim()
    commitHashMap = commitHashMap.split(' ').collectEntries { entry ->
        def pair = entry.split(':')
        [(pair.first()): pair.last()]
    }

    def componentsMissingMsg = ["UNABLE TO PROMOTE MISSING COMPONENTS IN ${projectInfo.deployFromNamespace}:"]
    projectInfo.componentsToPromote.each { component ->
        component.srcCommitHash = commitHashMap[component.name]
        if (component.srcCommitHash) {
            component.previousDeploymentBranch = projectUtils.getNonProdDeploymentBranchName(projectInfo, component, projectInfo.deployFromEnv)
            component.deploymentBranch = projectUtils.getNonProdDeploymentBranchName(projectInfo, component, projectInfo.deployToEnv)
            
            println "--> ${component.name} deployed in ${projectInfo.deployFromNamespace} src commit hash: ${component.srcCommitHash}"
        }
        else {
            componentsMissingMsg += "    ${component.name} NOT FOUND IN ${projectInfo.deployFromNamespace}"
        }
    }

    if (componentsMissingMsg.size() > 1) {
        loggingUtils.errorBanner(componentsMissingMsg)
    }
}

def runPromoteImagesStages(def projectInfo) {
    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployFromEnv),
                                      usernameVariable: 'FROM_IMAGE_REGISTRY_USERNAME',
                                      passwordVariable: 'FROM_IMAGE_REGISTRY_PWD'),
                     usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.deployToEnv),
                                      usernameVariable: 'TO_IMAGE_REGISTRY_USERNAME',
                                      passwordVariable: 'TO_IMAGE_REGISTRY_PWD')])
    {
        def stageTitle = "Promote And Tag Images"
        def copyImageStages = concurrentUtils.createParallelStages(stageTitle, projectInfo.componentsToPromote) { component ->
            loggingUtils.echoBanner("PROMOTING AND TAGGING ${component.name} IMAGE FROM ${projectInfo.deployFromEnv} TO ${projectInfo.deployToEnv}")
                                    
            def promoteTag = "${projectInfo.deployToEnv}-${component.srcCommitHash}"
            def copyImage =
                shCmd.copyImage(projectInfo.ENV_FROM,
                                'FROM_IMAGE_REGISTRY_USERNAME',
                                'FROM_IMAGE_REGISTRY_PWD',
                                component.id,
                                projectInfo.deployFromEnv,
                                projectInfo.ENV_TO,
                                'TO_IMAGE_REGISTRY_USERNAME',
                                'TO_IMAGE_REGISTRY_PWD',
                                component.id,
                                promoteTag)

            def tagImage = shCmd.tagImage(projectInfo.ENV_TO,
                                          'TO_IMAGE_REGISTRY_USERNAME',
                                          'TO_IMAGE_REGISTRY_PWD',
                                          component.id,
                                          promoteTag,
                                          projectInfo.deployToEnv)

            def msg = "--> ${component.id} image promoted and tagged as ${promoteTag} and ${projectInfo.deployToEnv}"

            sh  """
                ${copyImage}

                ${shCmd.echo ''}
                ${tagImage}

                ${shCmd.echo ''}
                ${shCmd.echo  "--> ${msg}"}
            """
        }

        parallel(copyImageStages)
    }
}

def createAndCheckoutDeploymentBranches(def projectInfo) {
    def scmBranchesFound = ["DEPLOYMENT BRANCHES FOUND:"]
    def scmBranchesCreated = ["DEPLOYMENT BRANCHES CREATED:"]
    concurrentUtils.runCloneGitReposStages(projectInfo, projectInfo.componentsToPromote) { component ->
        def checkDeployBranchScript = "git show-branch refs/remotes/origin/${component.deploymentBranch} || : | tr -d '[:space:]'"
        component.deployBranchExists = sh(returnStdout: true, script: checkDeployBranchScript)
        component.deployBranchExists = !component.deployBranchExists.isEmpty()

        def scmBranch = component.deployBranchExists ? component.deploymentBranch : component.previousDeploymentBranch
        if (scmBranch) {            
            sh "git checkout ${scmBranch}"            
            scmBranchesFound += "    ${component.name}: ${component.deploymentBranch}"
        }
        else {
            withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                sh """
                    ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY',
                                        "git branch ${component.deploymentBranch}",
                                        "git push origin ${component.deploymentBranch}:${component.deploymentBranch}")}
                """
            }
            scmBranchesCreated += "    ${component.name}: ${component.deploymentBranch}"
        }

        component.deploymentCommitHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
    }
    
    loggingUtils.echoBanner(scmBranchesFound, scmBranchesCreated)
}