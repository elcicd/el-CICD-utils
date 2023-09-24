/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 */

def gatherReleaseCandidateRepos(def projectInfo) {
    projectInfo.releaseCandidateComps = projectInfo.components.findAll{ component ->
        withCredentials([sshUserPrivateKey(credentialsId: component.scmDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
            versionTagScript = /git ls-remote --tags ${component.scmRepoUrl} '${projectInfo.versionTag}-*'/
            scmRepoTag = sh(returnStdout: true, script: shCmd.sshAgentBash('GITHUB_PRIVATE_KEY', versionTagScript)).trim()

            if (scmRepoTag) {
                scmRepoTag = scmRepoTag.substring(scmRepoTag.lastIndexOf('/') + 1)
                echo "-> RELEASE ${projectInfo.versionTag} COMPONENT FOUND: ${component.scmRepoName} / ${scmRepoTag}"
                component.releaseCandidateScmTag = scmRepoTag
                assert component.releaseCandidateScmTag ==~ /${projectInfo.versionTag}-[\w]{7}/ : msg
                   
            }
            else {
                echo "-> Release ${projectInfo.versionTag} component NOT found: ${component.scmRepoName}"
            }

            return component.releaseCandidateScmTag
        }
    }
}

def confirmPromotion(def projectInfo, def args) {
    def msgArray = [
        "CONFIRM CREATION OF RELEASE ${projectInfo.versionTag}",
        '',
        loggingUtils.BANNER_SEPARATOR,
        '',
        '-> ACTIONS TO BE TAKEN:',
        "   - A DEPLOYMENT BRANCH [${projectInfo.versionTag}] WILL BE CREATED IN THE SCM REPO ${projectInfo.projectModule.scmRepoName}",
        "   - IMAGES TAGGED AS ${projectInfo.versionTag} WILL BE PUSHED TO THE PROD IMAGE REGISTRY",
        '   - COMPONENTS NOT IN THIS RELEASE WILL BE REMOVED FROM ${projectInfo.prodEnv}',
        '',
        '-> COMPONENTS IN RELEASE:',
        projectInfo.releaseCandidateComps.collect { it.name },
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
        "Should Release ${projectInfo.versionTag} be created?"
    ]
    
    
    def msg = loggingUtils.createBanner(msgArray)g

    jenkinsUtils.displayInputWithTimeout(msg, args)
}

def checkoutAllRepos(def projectInfo) {
    def modules = [].addAll(projectInfo.releaseCandidateComps)
    modules.add(projectInfo.projectModule)
    concurrentUtils.runCloneGitReposStages(projectInfo, modules) { module ->
        sh "git checkout -B ${module.releaseCandidateScmTag}"
    }

    projectInfoUtils.cloneGitRepo(projectInfo.projectModule)
}

def createReleaseRepo(def projectInfo) {
    projectInfo.releaseCandidateComps.each { component ->
        compDeployWorkDir = "${projectInfo.module.workDir}/${component.scmRepoName}"
        sh"""
            git checkout -B ${projectInfo.versionTag}
            mkdir ${compDeployWorkDir}
            cp -R ${component.workDir}/.deploy ${compDeployWorkDir}
        """
    }
}