/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for bootstrapping CICD non-prod and prod environments
 * Should be called in order as written
 */

def verifyCicdJenkinsExists(def projectInfo, def isNonProd) {
    stage("Check if group's prod or non-prod CICD Jenkins exist") {
        def prodOrNonProd  = "${isNonProd ? 'NON-' : ''}PROD"
        pipelineUtils.echoBanner("VERIFY ${projectInfo.rbacGroup}'S ${prodOrNonProd} CICD JENKINS EXIST")

        sh """
            echo 'Verify group '${projectInfo.rbacGroup}' exists'
            oc get groups ${projectInfo.rbacGroup} --no-headers
        """

        def cicdMasterProjectExist =
            sh(returnStdout: true, script: "oc get rc --ignore-not-found -l app=jenkins-persistent -n ${projectInfo.cicdMasterNamespace}")

        if (!cicdMasterProjectExist) {
            onboardingUtils.deleteNamespaces(projectInfo.cicdMasterNamespace)

            def envs = isNonProd ? projectInfo.NON_PROD_ENVS : [projectInfo.PRE_PROD_ENV, projectInfo.PROD_ENV]
            createCicdNamespaceAndJenkins(projectInfo, envs)

            credentialUtils.copyElCicdMetaInfoBuildAndPullSecretsToGroupCicdServer(projectInfo, envs)

            stage('Push Image Repo Pull Secrets to rbacGroup Jenkins') {
                credentialUtils.pushElCicdCredentialsToCicdServer(projectInfo, envs)
            }
        }
        else {
            echo "EXISTENCE CONFIRMED: ${prodOrNonProd} CICD JENKINS EXIST"
        }
    }
}

def createCicdNamespaceAndJenkins(def projectInfo, def envs) {
    stage('Creating CICD namespaces and rbacGroup Jenkins') {
        def nodeSelectors = el.cicd.CICD_MASTER_NODE_SELECTORS ? "--node-selector='${el.cicd.CICD_MASTER_NODE_SELECTORS }'" : ''

        sh """
            ${pipelineUtils.shellEchoBanner("CREATING ${projectInfo.cicdMasterNamespace} PROJECT AND JENKINS FOR THE ${projectInfo.rbacGroup} GROUP")}

            oc adm new-project ${projectInfo.cicdMasterNamespace} ${nodeSelectors}

            ${shCmd.echo ''}
            oc new-app jenkins-persistent -p MEMORY_LIMIT=${el.cicd.JENKINS_MEMORY_LIMIT} \
                                          -p VOLUME_CAPACITY=${el.cicd.JENKINS_VOLUME_CAPACITY} \
                                          -p DISABLE_ADMINISTRATIVE_MONITORS=${el.cicd.JENKINS_DISABLE_ADMINISTRATIVE_MONITORS} \
                                          -p JENKINS_IMAGE_STREAM_TAG=${el.cicd.JENKINS_IMAGE_STREAM}:latest \
                                          -e OVERRIDE_PV_PLUGINS_WITH_IMAGE_PLUGINS=true \
                                          -e JENKINS_JAVA_OVERRIDES=-D-XX:+UseCompressedOops \
                                          -e TRY_UPGRADE_IF_NO_MARKER=true \
                                          -e CASC_JENKINS_CONFIG=${el.cicd.JENKINS_CONTAINER_CONFIG_DIR}/${el.cicd.JENKINS_CASC_FILE} \
                                          -n ${projectInfo.cicdMasterNamespace}
            ${shCmd.echo ''}
            ${shCmd.echo 'Creating nonrootbuilder SCC if necessary and applying to jenkins ServiceAccount'}
            oc apply -f ${el.cicd.JENKINS_CONFIG_DIR}/jenkinsServiceAccountSecurityContext.yml
            oc adm policy add-scc-to-user nonroot-builder -z jenkins -n ${projectInfo.cicdMasterNamespace}

            ${shCmd.echo ''}
            ${shCmd.echo 'Adding edit privileges for the rbacGroup to their CICD Automation Namespace'}
            oc policy add-role-to-group edit ${projectInfo.rbacGroup} -n ${projectInfo.cicdMasterNamespace}

            ${shCmd.echo ''}
            sleep 2
            ${shCmd.echo 'Waiting for Jenkins to come up...'}
            oc rollout status dc jenkins -n ${projectInfo.cicdMasterNamespace}
            ${shCmd.echo ''}
            ${shCmd.echo 'Jenkins up, sleep for 5 more seconds to make sure server REST api is ready'}
            sleep 5
        """
    }
}

def refreshAutomationPipelines(def projectInfo, def isNonProd) {
    stage('Refreshing shared pipelines') {
        def PIPELINE_DIR = isNonProd ? el.cicd.NON_PROD_AUTOMATION_PIPELINES_DIR : el.cicd.PROD_AUTOMATION_PIPELINES_DIR
        def PIPELINE_FOLDER = isNonProd ? el.cicd.NON_PROD_AUTOMATION : el.cicd.PROD_AUTOMATION
        
        def pipelineFiles
        dir(PIPELINE_DIR) {
            pipelineFiles = findFiles(glob: "**/*.xml").collect { it.name }
        }
                
        def msg = ['CREATING/UPDATING AUTOMATION PIPELINES:']
        msg.addAll(pipelineFiles)
        pipelineUtils.echoBanner(msg)
        
        def curlCommand = credentialUtils.getCurlCommand()
        def jenkinsUrl = "https://jenkins-${projectInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}"
            
        def curlDeletePipelineFolder = "${curlCommand} -X DELETE '${jenkinsUrl}/job/${PIPELINE_FOLDER}/'"
        
        def curlCreateCommand = "${curlCommand} -X POST -H 'Content-Type:text/xml'"
        
        def curlCreatePipelineFolder =
            "${curlCreateCommand} ${jenkinsUrl}/createItem?name=${PIPELINE_FOLDER} --data-binary @${el.cicd.EL_CICD_PIPELINES_DIR}/folder.xml"
        
        def curlPipeline = 
            "${curlCreateCommand} ${jenkinsUrl}/job/${PIPELINE_FOLDER}/createItem?name=\${FILE%.*} --data-binary @${PIPELINE_DIR}/\${FILE}"
        
        withCredentials([string(credentialsId: el.cicd.JENKINS_ACCESS_TOKEN, variable: 'JENKINS_ACCESS_TOKEN')]) {
            sh """
                ${shCmd.echo ''}
                ${curlDeletePipelineFolder}
                ${shCmd.echo ''}
                ${curlCreatePipelineFolder}

                for FILE in ${pipelineFiles.join(' ')}
                do
                    ${shCmd.echo ''}
                    ${shCmd.echo 'Creating ${FILE%.*} pipeline'}
                    ${curlPipeline}
                    ${shCmd.echo ''}
                done
            """
        }
    }
}
