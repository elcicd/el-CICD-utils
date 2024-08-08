/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

import groovy.transform.Field

@Field
static def cicd = [:]

def printParams(def params) {
    if (params) {
        paramsList = params.collect { param ->
            return "${param.key} = ${param.value}"
        }.reverse()
        loggingUtils.echoBanner('PIPELINE PARAMETERS:', '', paramsList)
    }
}

def initMetaData(Map metaData) {
    stage('Init Metadata') {
        loggingUtils.echoBanner("INITIALIZING METADATA...")

        el.cicd.putAll(metaData)

        el.cicd.EL_CICD_MASTER_NONPROD = el.cicd.EL_CICD_MASTER_NONPROD?.toBoolean() ?: false
        el.cicd.EL_CICD_MASTER_PROD = el.cicd.EL_CICD_MASTER_PROD?.toBoolean() ?: false

        el.cicd.EL_CICD_INSECURE_FLAG = el.cicd.EL_CICD_HELM_OCI_REGISTRY_ENABLE_TLS.toBoolean() ? '' : '--insecure'

        el.cicd.EL_CICD_DIR = "${WORKSPACE}/${el.cicd.EL_CICD_REPO}"
        el.cicd.EL_CICD_SCRIPTS_DIR = "${WORKSPACE}/${el.cicd.EL_CICD_REPO}/scripts"
        el.cicd.EL_CICD_CHART_DEPLOY_DIR = "${el.cicd.EL_CICD_DIR}/${el.cicd.CHART_DEPLOY_DIR}"
        el.cicd.EL_CICD_TEMPLATE_CHART_DIR = "${el.cicd.EL_CICD_DIR}/${el.cicd.TEMPLATE_CHART_DIR}"
        el.cicd.EL_CICD_CHARTS_TEMPLATE_DIR = "${el.cicd.EL_CICD_TEMPLATE_CHART_DIR}/templates"


        el.cicd.CONFIG_DIR = "${WORKSPACE}/${el.cicd.EL_CICD_CONFIG_REPO}"
        el.cicd.CONFIG_CHART_DEPLOY_DIR = "${el.cicd.CONFIG_DIR}/${el.cicd.CHART_DEPLOY_DIR}"
        el.cicd.CONFIG_JENKINS_DIR = "${el.cicd.CONFIG_DIR}/jenkins"
        el.cicd.BUILDER_STEPS_DIR = "${el.cicd.CONFIG_DIR}/builder-steps"
        el.cicd.SYSTEM_TEST_RUNNERS_DIR = "${el.cicd.CONFIG_DIR}/system-test-runners"
        el.cicd.HOOK_SCRIPTS_DIR = "${el.cicd.CONFIG_DIR}/hook-scripts"
        el.cicd.PROJECT_DEFS_DIR = "${el.cicd.CONFIG_DIR}/project-defs"

        el.cicd.TEMP_DIR = "/tmp/${BUILD_TAG}"

        el.cicd.TEST_ENVS = el.cicd.TEST_ENVS ? el.cicd.TEST_ENVS.split(':') : []

        el.cicd.devEnv = el.cicd.DEV_ENV.toLowerCase()
        el.cicd.testEnvs = el.cicd.TEST_ENVS.collect { it.toLowerCase() }
        el.cicd.preProdEnv = el.cicd.PRE_PROD_ENV.toLowerCase()

        el.cicd.hotfixEnv = el.cicd.HOTFIX_ENV.toLowerCase()

        el.cicd.nonProdEnvs = [el.cicd.devEnv]
        el.cicd.nonProdEnvs.addAll(el.cicd.testEnvs)
        el.cicd.nonProdEnvs.add(el.cicd.preProdEnv)

        el.cicd.prodEnv = el.cicd.PROD_ENV.toLowerCase()
    }
}

def node(Map args, Closure body) {
    def jenkinsAgent = args.agent ?: el.cicd.JENKINS_AGENT_DEFAULT

    def volumeDefs = [
        emptyDirVolume(mountPath: '/home/jenkins/agent', memory: true),
        emptyDirVolume(mountPath: '/home/.local/share/containers')
    ]

    if (args.isBuild) {
        volumeDefs += secretVolume(secretName: "${el.cicd.EL_CICD_BUILD_SECRETS_NAME}", mountPath: "${el.cicd.BUILDER_SECRETS_DIR}/")
    }
    
    def agentNamespace = null
    def serviceAccountName = el.cicd.JENKINS_SERVICE_ACCOUNT
    def elCicdMetaInfoConfigMapRef = /envFrom: [{"configMapRef": { "name": "${el.cicd.EL_CICD_META_INFO_NAME}" }, "prefix": "elcicd_"}]/
    if (args.isTest) {
        agentNamespace = "${args.projectId}-${args.testEnv}"
        serviceAccountName = "${args.projectId}-${el.cicd.TEST_SERVICE_ACCOUNT_SUFFIX}"
        elCicdMetaInfoConfigMapRef = ''
    }

    podTemplate([
        label: "${jenkinsAgent}",
        cloud: 'openshift',
        idleMinutes: "${args.isTest ? '0' : el.cicd.JENKINS_AGENT_MEMORY_IDLE_MINUTES}",
        imagePullSecrets: 'elcicd-jenkins-registry-credentials',
        namespace: agentNamespace,
        nodeSelector: "${el.cicd.JENKINS_AGENT_NODE_SELECETOR}",
        serviceAccount: "${serviceAccountName}",
        showRawYaml: true,
        volumes: volumeDefs,
        containers: [
            name: 'jnlp',
            alwaysPullImage: true,
            image: "${el.cicd.JENKINS_OCI_REGISTRY}/${el.cicd.JENKINS_AGENT_IMAGE_PREFIX}-${jenkinsAgent}"
            resourceRequestCpu: "${el.cicd.JENKINS_AGENT_CPU_REQUEST}",
            resourceRequestMemory: "${el.cicd.JENKINS_AGENT_MEMORY_REQUEST}",
            resourceLimitMemory: "${el.cicd.JENKINS_AGENT_MEMORY_LIMIT}"
        ]
        yaml: """
          spec:
            containers:
            - name: 'jnlp',
              envFrom:
                configMapRef:
                    name: "${el.cicd.EL_CICD_META_INFO_NAME}"
                    prefix: "elcicd_"
        """,
        yamlMergeStrategy: merge
    ]) {
        node(jenkinsAgent) {
            try {
                withCredentials([usernamePassword(credentialsId: el.cicd.EL_CICD_HELM_OCI_REGISTRY_CREDENTIALS,
                                usernameVariable: 'HELM_REGISTRY_USERNAME',
                                passwordVariable: 'HELM_REGISTRY_PASSWORD')]) {
                    initializePipeline(jenkinsAgent)

                    runHookScript(el.cicd.PRE, args)

                    if (args.teamId) {
                        args.teamInfo = projectInfoUtils.gatherTeamInfo(args.teamId)

                        if (args.projectId != el.cicd.UNDEFINED) {
                            args.projectInfo = projectInfoUtils.gatherProjectInfoStage(args.teamInfo, args.projectId)
                        }
                    }

                    runHookScript(el.cicd.INIT, args)

                    body.call(args)

                    runHookScript(el.cicd.ON_SUCCESS, args)
                }
            }
            catch (Exception | AssertionError exception) {
                (exception instanceof Exception) ?
                    loggingUtils.echoBanner("!!!! JOB FAILURE: EXCEPTION THROWN !!!!", "", "EXCEPTION: ${exception}") :
                    loggingUtils.echoBanner("!!!! JOB ASSERTION FAILED !!!!", "", "ASSERTION: ${exception}")

                runHookScript(el.cicd.ON_FAIL, args, exception)

                throw exception
            }
            finally {
                runHookScript(el.cicd.POST, args)
            }
        }
    }
}

def runHookScript(def prefix, def args, def exception = null) {
    dir(el.cicd.HOOK_SCRIPTS_DIR) {
        def hookScriptFile = findFiles(glob: "**/${prefix}-${args.pipelineName}.groovy")
        if (hookScriptFile) {
            def hookScript = load hookScriptFile[0].path

            echo "hook-script ${prefix}-${args.pipelineName}.groovy found: RUNNING..."

            exception ?  hookScript(exception, args) : hookScript(args)

            echo "hook-script ${prefix}-${args.pipelineName}.groovy COMPLETE"
        }
        else {
            echo "hook-script ${prefix}-${args.pipelineName}.groovy NOT found..."
        }
    }
}

def initializePipeline(def jenkinsAgent) {
    stage('Initializing pipeline') {
        loggingUtils.echoBanner("INITIALIZING PIPELINE...")

        sh """
            if [[ ! -z \$(ls -A) ]]
            then
                ${shCmd.echo('Cleaning workspace from previous build...')}
                rm -rf ./*
            fi

            mkdir -p '${el.cicd.TEMP_DIR}'

            ${shCmd.echo("\n${loggingUtils.BANNER_SEPARATOR}\n")}
            ${shCmd.echo "Jenkins Agent: ${jenkinsAgent}"}
            ${shCmd.echo "\n${loggingUtils.BANNER_SEPARATOR}\n"}
            ${shCmd.echo 'OCP Runtime'}
            oc version
            ${shCmd.echo "\n${loggingUtils.BANNER_SEPARATOR}\n"}
            ${shCmd.echo 'Helm Version'}
            helm version
            ${shCmd.echo ''}
            ${shCmd.echo '\${HELM_REGISTRY_PASSWORD}'} | \
                helm registry login ${el.cicd.EL_CICD_INSECURE_FLAG} \
                    -u \${HELM_REGISTRY_USERNAME} --password-stdin \
                    ${el.cicd.EL_CICD_HELM_OCI_REGISTRY_DOMAIN}
            ${shCmd.echo "\n${loggingUtils.BANNER_SEPARATOR}\n"}
            git config --global user.name ${el.cicd.EL_CICD_ORGANIZATION}
            git config --global user.email "${el.cicd.EL_CICD_ORGANIZATION}@donotreply.com"
            ${shCmd.echo "\n${loggingUtils.BANNER_SEPARATOR}\n"}
        """

        dir (el.cicd.EL_CICD_DIR) {
            git url: el.cicd.EL_CICD_GIT_REPO,
                branch: el.cicd.EL_CICD_GIT_REPO_BRANCH_NAME,
                credentialsId: el.cicd.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
        }

        dir (el.cicd.CONFIG_DIR) {
            git url: el.cicd.EL_CICD_CONFIG_GIT_REPO,
                branch: el.cicd.EL_CICD_CONFIG_GIT_REPO_BRANCH_NAME,
                credentialsId: el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
        }
    }
}