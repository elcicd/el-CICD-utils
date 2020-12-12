/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility class defining the Jenkins agents' pods, executing the pipeline code on agent pods, el-CICD scripting framework around pods.
 * Also processes el-CICD metadata into global map.
 */

import groovy.transform.Field

@Field
static def cicd = [:]

def initMetaData(Map metaData) {
    cicd.putAll(metaData)

    cicd.TEST_ENVS = cicd.TEST_ENVS.split(':')

    cicd.testEnvs = cicd.TEST_ENVS.collect { it.toLowerCase() }
    cicd.devEnv = cicd.DEV_ENV.toLowerCase()
    cicd.prodEnv = cicd.PROD_ENV.toLowerCase()

    cicd.IGNORE = ''
    cicd.PROMOTE = 'PROMOTE'
    cicd.REMOVE = 'REMOVE'

    cicd.PRE = 'pre'
    cicd.POST = 'post'
    cicd.ON_SUCCESS = 'on-success'
    cicd.ON_FAIL = 'on-fail'

    cicd.BUILDER = 'builder'
    cicd.TESTER = 'tester'
    cicd.SCANNER = 'scanner'

    cicd.INACTIVE = 'INACTIVE'

    cicd.CLEAN_K8S_RESOURCE_COMMAND = "egrep -v -h 'namespace:|creationTimestamp:|uid:|selfLink:|resourceVersion:|generation:'"

    cicd.DEPLOYMENT_BRANCH_PREFIX = 'deployment'

    cicd.SANDBOX_NAMESPACE_BADGE = 'sandbox'

    cicd = cicd.asImmutable()
}

def node(Map args, Closure body) {
    assert args.agent

    def podLabel = args.agentName ?: args.agent

    podTemplate([
        label: "${podLabel}",
        cloud: 'openshift',
        serviceAccount: 'jenkins',
        podRetention: onFailure(),
        idleMinutes: "${el.cicd.JENKINS_AGENT_MEMORY_IDLE_MINUTES}",
        containers: [
            containerTemplate(
                name: 'jnlp',
                image: "${el.cicd.OCP_IMAGE_REPO}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${args.agent}:latest",
                alwaysPullImage: true,
                args: '${computer.jnlpmac} ${computer.name}',
                resourceRequestMemory: '512Mi',
                resourceLimitMemory: "${el.cicd.JENKINS_AGENT_MEMORY_LIMIT}",
                resourceRequestCpu: '100m',
                resourceLimitCpu: "${el.cicd.JENKINS_AGENT_CPU_LIMIT}"
            )
        ]
    ]) {
        node(podLabel) {
            try {
                initializeStage()

                runHookScript(el.cicd.PRE, args)

                if (args.projectId) {
                    args.projectInfo = pipelineUtils.gatherProjectInfoStage(args.projectId)
                }

                body.call(args)

                runHookScript(el.cicd.ON_SUCCESS, args)
            }
            catch (Exception exception) {
                runHookScript(el.cicd.ON_FAIL, args, exception)

                throw exception
            }
            finally {
                runHookScript(el.cicd.POST, args)
            }
        }
    }
}

def runHookScript(def prefix, def args) {
    runHookScript(prefix, args, null)
}

def runHookScript(def prefix, def args, def exception) {
    dir(el.cicd.HOOK_SCRIPTS_DIR) {
        pipelineUtils.spacedEcho("Searching for hook-script ${prefix}-${args.pipelineTemplateName}.groovy...")

        def hookScriptFile = findFiles(glob: "**/${prefix}-${args.pipelineTemplateName}.groovy")
        if (hookScriptFile) {
            def hookScript = load hookScriptFile[0].path

            pipelineUtils.spacedEcho("hook-script ${prefix}-${args.pipelineTemplateName}.groovy found: RUNNING...")

            exception ?  hookScript(exception, args) : hookScript(args)

            pipelineUtils.spacedEcho("hook-script ${prefix}-${args.pipelineTemplateName}.groovy COMPLETE")
        }
        else {
            pipelineUtils.spacedEcho("hook-script ${prefix}-${args.pipelineTemplateName}.groovy NOT found...")
        }
    }
}

def initializeStage() {
    stage('Initializing') {
        pipelineUtils.echoBanner("INITIALIZING...")

        el.cicd.CONFIG_DIR = "${WORKSPACE}/el-CICD-config"
        el.cicd.AGENTS_DIR = "${el.cicd.CONFIG_DIR}/agents"
        el.cicd.BUILDER_STEPS_DIR = "${el.cicd.CONFIG_DIR}/builder-steps"
        el.cicd.OKD_TEMPLATES_DIR = "${el.cicd.CONFIG_DIR}/default-okd-templates"
        el.cicd.HOOK_SCRIPTS_DIR = "${el.cicd.CONFIG_DIR}/hook-scripts"
        el.cicd.PROJECT_DEFS_DIR = "${el.cicd.CONFIG_DIR}/project-defs"

        el.cicd.TEMP_DIR="/tmp/${BUILD_TAG}"
        sh """
            rm -rf ${WORKSPACE}/*
            mkdir -p ${el.cicd.TEMP_DIR}
            oc version
        """
        el.cicd.TEMPLATES_DIR="${el.cicd.TEMP_DIR}/templates"
        el.cicd.BUILDCONFIGS_DIR = "${el.cicd.TEMP_DIR}/buildconfigs"
        sh """
            mkdir -p ${el.cicd.BUILDCONFIGS_DIR}
            mkdir -p ${el.cicd.TEMPLATES_DIR}
        """

        el.cicd.RELEASE_VERSION_PREFIX = 'v'

        el.cicd = el.cicd.asImmutable()

        dir (el.cicd.CONFIG_DIR) {
            git url: el.cicd.EL_CICD_CONFIG_REPOSITORY,
                branch: el.cicd.EL_CICD_CONFIG_REPOSITORY_BRANCH_NAME,
                credentialsId: el.cicd.EL_CICD_CONFIG_REPOSITORY_READ_ONLY_GITHUB_PRIVATE_KEY_ID
        }
    }
}