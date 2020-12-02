/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Defines the bulk of the create-all-jenkins-agents pipeline.  Called inline from the
 * a realized el-CICD/resources/buildconfigs/create-all-jenkins-agents-pipeline-template.
 *
 */

def call(Map args) {
    elCicdCommons.initialize()

    def agentDockerfiles

    stage('Update Jenkins') {
        if (args.updateJenkinsImage) {
            pipelineUtils.echoBanner('UPDATE JENKINS IMAGE')

            sh "oc import-image jenkins -n openshift"
        }
        else {
            pipelineUtils.echoBanner('SKIPPING UPDATE JENKINS IMAGE')
        }
    }

    stage('Create All Agents') {
        pipelineUtils.echoBanner('CREATE JENKINS AGENTS:', agentDockerfiles)

        dir(el.cicd.AGENTS_DIR) {
            el.cicd.JENKINS_AGENT_NAMES.split(':').each { agentName ->
                sh """
                    if [[ -z \$(oc get --ignore-not-found bc/${JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -n openshift) ]]
                    then 
                        cat ./Dockerfile.${agentName} | oc new-build -D - --name ${JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -n openshift
                    else
                        oc start-build ${JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -n openshift
                    fi
                    sleep 10

                    oc logs -f bc/${JENKINS_AGENT_IMAGE_PREFIX}-${agentName} -n openshift --request-timeout=5m
                """
            }
        }
    }
}
