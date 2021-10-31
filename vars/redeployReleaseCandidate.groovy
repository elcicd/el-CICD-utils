/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the deploy-to-production pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/deploy-to-production-pipeline-template.
 *
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    projectInfo.releaseCandidateTag = args.releaseCandidateTag
    projectInfo.deployToEnv = projectInfo.preProdEnv
    projectInfo.deployToNamespace = projectInfo.preProdNamespace

    stage('Gather all git branches, tags, and source commit hashes') {
        pipelineUtils.echoBanner("GATHER ALL GIT BRANCHES, TAGS, AND SOURCE COMMIT HASHES")

        deployToProductionUtils.gatherAllVersionGitTagsAndBranches(projectInfo)

        if (!projectInfo.microServices.find{ it.releaseCandidateGitTag })  {
            pipelineUtils.errorBanner("${projectInfo.releaseCandidateTag}: BAD VERSION TAG", "RELEASE TAG(S) MUST EXIST")
        }
    }

    stage('Verify release candidate images exist for redeployment') {
        pipelineUtils.echoBanner("VERIFY REDEPLOYMENT CAN PROCEED FOR RELEASE CANDIDATE ${projectInfo.releaseCandidateTag}:",
                                 projectInfo.microServices.findAll { it.releaseCandidateGitTag }.collect { it.name }.join(', '))

        def allImagesExist = true
        withCredentials([string(credentialsId: el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"],
                                variable: 'IMAGE_REPO_ACCESS_TOKEN')]) {
            def imageRepoUserName = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"]
            def imageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_POSTFIX}"]

            projectInfo.microServices.each { microService ->
                if (microService.releaseCandidateGitTag) {
                    def srcCommitHash = microService.releaseCandidateGitTag.split('-').last()
                    def imageUrl = "docker://${imageRepo}/${microService.id}:${projectInfo.preProdEnv}-${srcCommitHash}"

                    def tlsVerify = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"] ?: true
                    def skopeoInspectCmd = "skopeo inspect --raw --tls-verify=${tlsVerify} --creds"
                    def imageFound = sh(returnStdout: true,
                                        script: "${skopeoInspectCmd} ${imageRepoUserName}:\${IMAGE_REPO_ACCESS_TOKEN} ${imageUrl} || :").trim()

                    def msg = imageFound ? "REDEPLOYMENT CAN PROCEED FOR ${microService.name}" :
                                           "-> ERROR: no image found: ${imageRepo}/${microService.id}:${projectInfo.preProdEnv}-${srcCommitHash}"
                    echo msg

                    allImagesExist = allImagesExist && imageFound
                }
            }
        }

        if (!allImagesExist) {
            def msg = "BUILD FAILED: Missing image(s) to deploy in ${projectInfo.PRE_PROD_ENV} for release candidate ${projectInfo.releaseCandidateTag}"
            pipelineUtils.errorBanner(msg)
        }
    }

    stage('Checkout all release candidate microservice repositories') {
        pipelineUtils.echoBanner("CLONE MICROSERVICE REPOSITORIES")

        projectInfo.microServices.each { microService ->
            if (microService.releaseCandidateGitTag) {
                def srcCommitHash = microService.releaseCandidateGitTag.split('-').last()
                microService.deploymentBranch = "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${projectInfo.preProdEnv}-${srcCommitHash}"
                pipelineUtils.cloneGitRepo(microService, microService.deploymentBranch)
            }
        }
    }

    stage('Confirm release candidate deployment') {
        input("""

            ===========================================
            CONFIRM REDEPLOYMENT OF ${projectInfo.releaseCandidateTag} to ${projectInfo.preProdEnv}
            ===========================================

            *******
            -> Microservices included in this release candidate to be deployed:
            ${projectInfo.microServices.findAll { it.releaseCandidateGitTag }.collect { it.name }.join(', ')}
            *******

            *******
            -> ${projectInfo.deployToNamespace} will be cleaned of all other project resources before deployment
            *******

            ===========================================
            PLEASE REREAD THE ABOVE RELEASE MANIFEST CAREFULLY AND PROCEED WITH CAUTION

            ARE YOU SURE YOU WISH TO PROCEED?
            ===========================================
        """)
    }

    stage('Tag images') {
        pipelineUtils.echoBanner("TAG IMAGES TO ${projectInfo.PRE_PROD_ENV}:",
                                 "${projectInfo.microServices.findAll { it.releaseCandidateGitTag }.collect { it.name } .join(', ')}")

        withCredentials([string(credentialsId: el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_ACCESS_TOKEN_ID_POSTFIX}"],
                                variable: 'PRE_PROD_IMAGE_REPO_ACCESS_TOKEN')]) {
            def userNamePwd =
                el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_USERNAME_POSTFIX}"] + ":\${PRE_PROD_IMAGE_REPO_ACCESS_TOKEN}"
            def tlsVerify = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_ENABLE_TLS_POSTFIX}"] ?: true
            def srcTlsVerify = "--src-tls-verify=${tlsVerify}"
            def destTlsVerify = "--dest-tls-verify=${tlsVerify}"
            def skopeoCopyComd = 
                "skopeo copy --src-creds ${userNamePwd} --dest-creds ${userNamePwd} ${srcTlsVerify} ${destTlsVerify}"

            def preProdImageRepo = el.cicd["${projectInfo.PRE_PROD_ENV}${el.cicd.IMAGE_REPO_POSTFIX}"]

            projectInfo.microServices.each { microService ->
                if (microService.releaseCandidateGitTag) {
                    def preProdImageUrl = "${preProdImageRepo}/${microService.id}"

                    def msg = "${microService.name}: ${projectInfo.releaseCandidateTag} " +
                              "TAGGED AS ${projectInfo.preProdEnv} and ${projectInfo.preProdEnv}-${microService.srcCommitHash}"

                    sh """
                        ${shCmd.echo ''}
                        ${skopeoCopyComd} docker://${preProdImageUrl}:${projectInfo.releaseCandidateTag} \
                                          docker://${preProdImageUrl}:${projectInfo.preProdEnv}-${microService.srcCommitHash}

                        ${shCmd.echo ''}
                        ${skopeoCopyComd} docker://${preProdImageUrl}:${projectInfo.releaseCandidateTag} \
                                          docker://${preProdImageUrl}:${projectInfo.preProdEnv}

                        ${shCmd.echo '',
                                    '******',
                                    msg,
                                    '******'}
                    """
                }
            }
        }
    }

    deployMicroServices(projectInfo: projectInfo,
                        microServices: projectInfo.microServices.findAll { it.releaseCandidateGitTag },
                        imageTag: projectInfo.preProdEnv,
                        recreateAll: true)
}
