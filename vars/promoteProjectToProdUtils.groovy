/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def verifyProjectReleaseVersion(def projectInfo) {
    withCredentials([sshUserPrivateKey(credentialsId: projectInfo.projectModule.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
        versionTagScript = /git ls-remote ${projectInfo.projectModule.scmRepoUrl} '${projectInfo.releaseVersion}'/
        scmReleaseVersionBranch = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', versionTagScript)).trim()

        if (scmReleaseVersionBranch) {
            loggingUtils.errorBanner("RELEASE VERSION ${projectInfo.releaseVersion} HAS ALREADY BEEN PROMOTED")
        }
    }
}

def gatherReleaseCandidateRepos(def projectInfo) {
    projectInfo.componentsToPromote = projectInfo.components.findAll{ component ->
        withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
            versionTagScript = /git ls-remote --tags ${component.scmRepoUrl} '${projectInfo.releaseVersion}-*'/
            scmRepoTag = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', versionTagScript)).trim()

            if (scmRepoTag) {
                scmRepoTag = scmRepoTag.substring(scmRepoTag.lastIndexOf('/') + 1)
                echo "--> RELEASE ${projectInfo.releaseVersion} COMPONENT FOUND: ${component.scmRepoName} / ${scmRepoTag}"
            }
            else {
                echo "--> Release ${projectInfo.releaseVersion} component NOT found: ${component.scmRepoName}"
            }

            return component.releaseCandidateScmTag
        }
    }

    if (!projectInfo.componentsToPromote) {
        loggingUtils.errorBanner("RELEASE CANDIDATE ${projectInfo.releaseVersion} NOT FOUND IN SCM")
    }
}

def checkoutReleaseCandidateRepos(def projectInfo) {
    projectInfo.projectModule.releaseCandidateScmTag = projectInfo.releaseVersion

    def modules = [projectInfo.projectModule]
    modules.addAll(projectInfo.componentsToPromote)

    concurrentUtils.runCloneGitReposStages(projectInfo, modules) { module ->
        sh """
            ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', 'git fetch --all --tags')}

            if [[ ! -z \$(git tag -l ${module.releaseCandidateScmTag}) ]]
            then
                git checkout tags/${module.releaseCandidateScmTag}
            else
                ${shCmd.echo('', "--> Creating release branch ${module.releaseCandidateScmTag} in project repo ${projectInfo.projectModule.repoName}")}
                git switch -c ${module.releaseCandidateScmTag}
                 ${shCmd.echo('')}
            fi
        """
    }
}

def createReleaseVersionComponentSubCharts(def projectInfo) {
    deploymentUtils.setupDeploymentDirs(projectInfo, projectInfo.componentsToPromote)

    dir (projectInfo.projectModule.workDir) {
        projectInfo.componentsToPromote.each { component ->
            sh"""
                mkdir -p charts/${component.name}
                cp -R ${component.deploymentDir}/* charts/${component.name}
            """
        }
    }
}

def createReleaseVersionUmbrellaChart(def projectInfo) {
    dir (projectInfo.projectModule.workDir) {
        sh """
            cp -R ${el.cicd.EL_CICD_CHARTS_TEMPLATE_DIR} \
                  ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/.helmignore \
                  ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/${el.cicd.EL_CICD_POST_RENDER_KUSTOMIZE} .

            helm repo add elCicdCharts ${el.cicd.EL_CICD_HELM_REPOSITORY}
            helm template --set-string elCicdDefs.EL_CICD_MASTER_NAMESPACE=${projectInfo.teamInfo.cicdMasterNamespace} \
                          -f ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/project-values.yaml \
                          render-values-yaml elCicdCharts/elCicdChart | sed -E '/^#|^---/d' > values.yaml

            helm template --set-string elCicdDefs.VERSION=${projectInfo.releaseVersion} \
                          --set-string elCicdDefs.HELM_REPOSITORY_URL=${el.cicd.EL_CICD_HELM_REPOSITORY} \
                          -f ${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/helm-chart-yaml-values.yaml \
                          ${projectInfo.id} elCicdCharts/elCicdChart | sed -E '/^#|^---/d' > Chart.yaml
        """
    }
}

def confirmPromotion(def projectInfo, def args) {
    def msgArray = [
        "CONFIRM CREATION OF RELEASE ${projectInfo.releaseVersion}",
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        '-> ACTIONS TO BE TAKEN:',
        "   - A DEPLOYMENT BRANCH [${projectInfo.releaseVersion}] WILL BE CREATED IN THE SCM REPO ${projectInfo.projectModule.scmRepoName}",
        "   - IMAGES TAGGED AS ${projectInfo.releaseVersion} WILL BE PUSHED TO THE PROD IMAGE REGISTRY",
        '   - COMPONENTS NOT IN THIS RELEASE WILL BE REMOVED FROM ${projectInfo.prodEnv}',
        '',
        '-> COMPONENTS IN RELEASE:',
        projectInfo.componentsToPromote.collect { it.name },
    ]

    def compsNotInRelease = projectInfo.components.findAll{ !it.releaseCandidateScmTag }.collect { it.name }
    if (compsNotInRelease) {
        msgArray += [
            '',
            '-> COMPONENTS NOT IN THIS RELEASE:',
            projectInfo.components.findAll{ !it.releaseCandidateScmTag }.collect { it.name },
        ]
    }

    msgArray += [
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        "WARNING: A Release Candidate CAN ONLY BE PROMOTED ONCE",
        '',
        'PLEASE CAREFULLY REVIEW THE ABOVE RELEASE MANIFEST AND PROCEED WITH CAUTION',
        '',
        "Should Release ${projectInfo.releaseVersion} be created?"
    ]


    def msg = loggingUtils.createBanner(msgArray)

    jenkinsUtils.displayInputWithTimeout(msg, args)
}

def pushReleaseVersion(def projectInfo) {
    echo "projectInfo.projectModule.scmDeployKeyJenkinsId: ${projectInfo.projectModule.scmDeployKeyJenkinsId}"
    withCredentials([sshUserPrivateKey(credentialsId: projectInfo.projectModule.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
        dir(projectInfo.projectModule.workDir) {
            sh """
                git add -A
                git commit -am 'creating ${projectInfo.id} release version ${projectInfo.releaseVersion}'
                ${shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', "git push origin ${projectInfo.releaseVersion}:${projectInfo.releaseVersion}")}
            """
        }
    }
}

def promoteReleaseCandidateImages(def projectInfo) {
    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.preProdEnv),
                                      usernameVariable: 'FROM_IMAGE_REGISTRY_USERNAME',
                                      passwordVariable: 'FROM_IMAGE_REGISTRY_PWD'),
                     usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.prodEnv),
                                      usernameVariable: 'TO_IMAGE_REGISTRY_USERNAME',
                                      passwordVariable: 'TO_IMAGE_REGISTRY_PWD')])
    {
        def stageTitle = "Promoting to Prod"
        def copyImageStages = concurrentUtils.createParallelStages(stageTitle, projectInfo.componentsToPromote) { component ->
            loggingUtils.echoBanner("PROMOTING AND TAGGING ${component.name} IMAGE FROM ${projectInfo.preProdEnv} TO ${projectInfo.prodEnv}")
                                    
            def copyImage =
                shCmd.copyImage(projectInfo.PRE_PROD_ENV,
                                'FROM_IMAGE_REGISTRY_USERNAME',
                                'FROM_IMAGE_REGISTRY_PWD',
                                component.id,
                                component.releaseVersion,
                                projectInfo.PROD_ENV,
                                'TO_IMAGE_REGISTRY_USERNAME',
                                'TO_IMAGE_REGISTRY_PWD',
                                component.id,
                                component.releaseVersion)
                                
            def msg = "--> ${component.id} image promoted and tagged as ${component.releaseVersion}"

            sh  """
                ${copyImage}

                ${shCmd.echo ''}
                ${shCmd.echo msg}
            """
        }

        parallel(copyImageStages)
    }
}