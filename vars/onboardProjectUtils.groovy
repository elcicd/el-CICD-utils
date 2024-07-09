/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for onboading applications into the el-CICD framework
 */

def setupTeamCicdServer(def teamInfo) {
    loggingUtils.echoBanner("CONFIGURING JENKINS IN NAMESPACE ${teamInfo.cicdMasterNamespace} FOR TEAM ${teamInfo.id}")

    def jenkinsDefs = getJenkinsConfigValues(teamInfo)
    def jenkinsConfigFile = "${teamInfo.id}-jenkins-config-values.yaml"
    writeYaml(file: jenkinsConfigFile, data: jenkinsDefs)

    sh """
        ${shCmd.echo ''}
        cat ${jenkinsConfigFile}

        ${shCmd.echo ''}
        helm upgrade --install --atomic --create-namespace --history-max=1 --timeout 10m0s \
            -f ${jenkinsConfigFile} \
            --set-file elCicdDefs.JENKINS_CASC_FILE=${el.cicd.CONFIG_JENKINS_DIR}/${el.cicd.JENKINS_CICD_CASC_FILE} \
            --set-file elCicdDefs.JENKINS_PLUGINS_FILE=${el.cicd.CONFIG_JENKINS_DIR}/${el.cicd.JENKINS_CICD_PLUGINS_FILE} \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/prod-pipeline-setup-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/elcicd-jenkins-pipeline-template-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/jenkins-config-values.yaml \
            -f ${el.cicd.CONFIG_CHART_DEPLOY_DIR}/default-team-server-values.yaml \
            -n ${teamInfo.cicdMasterNamespace} \
            jenkins \
            ${el.cicd.EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart

        sleep 3
        ${shCmd.echo ''}
        ${shCmd.echo 'JENKINS UP'}
    """
}

def getJenkinsConfigValues(def teamInfo) {
    def jenkinsConfigValues = [elCicdDefs: [:]]
    def elCicdDefs= jenkinsConfigValues.elCicdDefs

    createElCicdProfiles(jenkinsConfigValues)
    jenkinsConfigValues.elCicdProfiles += ['user-group', 'jenkinsPersistent']

    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        elCicdDefs.NONPROD_REGISTRY_ENVS = el.cicd.nonProdEnvs
    }

    if (el.cicd.EL_CICD_MASTER_PROD) {
        elCicdDefs.PROD_REGISTRY_ENVS = el.cicd.EL_CICD_MASTER_NONPROD ? [el.cicd.prodEnv] : [el.cicd.preProdEnv, el.cicd.prodEnv]
    }

    elCicdDefs.EL_CICD_GIT_REPOS_READ_ONLY_KEYS = [
        el.cicd.EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID,
        el.cicd.EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID
    ]

    elCicdDefs.USER_GROUP = teamInfo.id
    elCicdDefs.EL_CICD_META_INFO_NAME = el.cicd.EL_CICD_META_INFO_NAME
    elCicdDefs.EL_CICD_BUILD_SECRETS_NAME = el.cicd.EL_CICD_BUILD_SECRETS_NAME
    elCicdDefs.EL_CICD_MASTER_NAMESPACE = el.cicd.EL_CICD_MASTER_NAMESPACE
    elCicdDefs.JENKINS_IMAGE = "${el.cicd.JENKINS_OCI_REGISTRY}/${el.cicd.JENKINS_IMAGE_NAME}"
    elCicdDefs.JENKINS_URL = "${teamInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}"
    elCicdDefs.JENKINS_UC = el.cicd.JENKINS_UC
    elCicdDefs.JENKINS_UC_INSECURE = el.cicd.JENKINS_UC_INSECURE
    elCicdDefs.OPENSHIFT_ENABLE_OAUTH = el.cicd.OKD_VERSION ? 'true' : 'false'
    elCicdDefs.JENKINS_CPU_REQUEST = el.cicd.JENKINS_CICD_CPU_REQUEST
    elCicdDefs.JENKINS_MEMORY_REQUEST = el.cicd.JENKINS_CICD_MEMORY_REQUEST
    elCicdDefs.JENKINS_MEMORY_LIMIT = el.cicd.JENKINS_CICD_MEMORY_LIMIT
    elCicdDefs.JENKINS_AGENT_CPU_REQUEST = el.cicd.JENKINS_AGENT_CPU_REQUEST
    elCicdDefs.JENKINS_AGENT_MEMORY_REQUEST = el.cicd.JENKINS_AGENT_MEMORY_REQUEST
    elCicdDefs.JENKINS_AGENT_MEMORY_LIMIT = el.cicd.JENKINS_AGENT_MEMORY_LIMIT
    elCicdDefs.VOLUME_CAPACITY = el.cicd.JENKINS_CICD_VOLUME_CAPACITY
    elCicdDefs.JENKINS_CONFIG_FILE_PATH = el.cicd.JENKINS_CONFIG_FILE_PATH

    return jenkinsConfigValues
}

def setupProjectPipelines(def projectInfo) {
    def projectDefs = getElCicdChartProjectPipelineValues(projectInfo)
    def pipelinesValuesFile = "${projectInfo.id}-pipelines-config-values.yaml"
    writeYaml(file: pipelinesValuesFile, data: projectDefs)

    sh """
        ${shCmd.echo '', "${projectInfo.id} PROJECT VALUES INJECTED INTO el-CICD HELM CHART:"}
        cat ${pipelinesValuesFile}

        ${shCmd.echo '', "UPGRADE/INSTALLING CICD pipeline definitions for project ${projectInfo.id}"}

        ${shCmd.echo ''}
        helm upgrade --install --atomic --history-max=1 \
            -f ${pipelinesValuesFile} \
            -f ${el.cicd.CONFIG_CHART_DEPLOY_DIR}/default-project-pipeline-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/project-pipelines-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.JENKINS_CHART_DEPLOY_DIR}/elcicd-jenkins-pipeline-template-values.yaml \
            -n ${projectInfo.teamInfo.cicdMasterNamespace} \
            ${projectInfo.id}-${el.cicd.PIPELINES_POSTFIX} \
            ${el.cicd.EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
    """
}

def setupProjectCredentials(def projectInfo) {
    def modulesSshKeyDefs = createProjectSshKeyValues(projectInfo)
    def modulesSshValuesFile = "${projectInfo.id}-module-ssh-values.yaml"
    writeYaml(file: modulesSshValuesFile, data: modulesSshKeyDefs)

    sh """
        ${shCmd.echo '', "UPGRADE/INSTALLING credentials for project ${projectInfo.id}"}

        ${shCmd.echo ''}
        helm upgrade --install --atomic --history-max=1 \
            -f ${modulesSshValuesFile} \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/git-secret-values.yaml \
            -n ${projectInfo.teamInfo.cicdMasterNamespace} \
            ${projectInfo.id}-${el.cicd.CREDENTIALS_POSTFIX} \
            ${el.cicd.EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
    """
}

def setProjectSdlc(def projectInfo) {
    setupProjectEnvironments(projectInfo)

    if (projectInfo.staticPvs) {
        echo("--> DEPLOY PERSISTENT VOLUMES DEFINITIONS FOR PROJECT ${projectInfo.id}")
        setupProjectPvResources(projectInfo)
    }
    else {
        echo("--> NO PERSISTENT VOLUME DEFINITIONS DEFINED FOR PROJECT ${projectInfo.id}: SKIPPING")
    }
}

def setupProjectEnvironments(def projectInfo) {
    def projectDefs = getElCicdChartProjectEnvironmentsValues(projectInfo)
    def environmentsValuesFile = "${projectInfo.id}-environments-config-values.yaml"
    writeYaml(file: environmentsValuesFile, data: projectDefs)

    sh """
        ${shCmd.echo '', "${projectInfo.id} PROJECT VALUES INJECTED INTO el-CICD HELM CHART:"}
        cat ${environmentsValuesFile}

        ${shCmd.echo '', "UPGRADE/INSTALLING SDLC environments for project ${projectInfo.id}"}

        ${shCmd.echo ''}
        chmod +x ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/onboarding-post-renderer.sh
        helm upgrade --wait --wait-for-jobs --install --history-max=1 \
            -f ${environmentsValuesFile} \
            -f ${el.cicd.CONFIG_CHART_DEPLOY_DIR}/resource-quotas-values.yaml \
            -f ${el.cicd.CONFIG_CHART_DEPLOY_DIR}/default-project-environments-values.yaml \
            -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/project-environments-values.yaml \
            --post-renderer ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/onboarding-post-renderer.sh \
            --post-renderer-args ${projectInfo.teamInfo.id} \
            --post-renderer-args ${projectInfo.id} \
            -n ${projectInfo.teamInfo.cicdMasterNamespace} \
            ${projectInfo.id}-${el.cicd.ENVIRONMENTS_POSTFIX} \
            ${el.cicd.EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
    """
}

def setupProjectPvResources(def projectInfo) {
    if (projectInfo.staticPvs) {
        def pvValues = getPvCicdConfigValues(projectInfo)
        if (pvValues.elCicdDefs.VOLUME_OBJ_NAMES) {
            def volumeCicdConfigValues = writeYaml(data: pvValues, returnText: true)

            def volumeCicdConfigFile = "${projectInfo.id}-volume-cicd-config-values.yaml"
            writeFile(file: volumeCicdConfigFile, text: volumeCicdConfigValues)

            sh """
                ${shCmd.echo '', "${projectInfo.id} PROJECT VOLUME VALUES:"}
                cat ${volumeCicdConfigFile}
                
                helm upgrade --install \
                    -f ${volumeCicdConfigFile} \
                    -f ${el.cicd.EL_CICD_DIR}/${el.cicd.CICD_CHART_DEPLOY_DIR}/project-persistent-volume-values.yaml \
                    -n ${projectInfo.teamInfo.cicdMasterNamespace} \
                    ${projectInfo.id}-${el.cicd.PVS_POSTFIX} \
                    ${el.cicd.EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
            """
        }
        else {
            echo "--> NO VOLUMES DEFINED FOR MOUNTING TO PODS: SKIPPING"
        }
    }
}

def getPvCicdConfigValues(def projectInfo) {
    def pvValues = [elCicdDefs: [:]]
    def pvNamespaces = [:]
    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        pvNamespaces.putAll(projectInfo.nonProdNamespaces)
    }

    if (el.cicd.EL_CICD_MASTER_PROD) {
        pvNamespaces.putAll(projectInfo.prodNamespaces)
    }

    pvValues.elCicdDefs.VOLUME_OBJ_NAMES = []
    projectInfo.staticPvs.each { pv ->
        pv.envs.each { env ->
            def namespace = pvNamespaces[env]
            if (namespace) {
                projectInfo.components.each { component ->
                    if (component.staticPvs.contains(pv.name)) {
                        def objName = "${el.cicd.PV_PREFIX}-${pv.name}-${component.name}-${env}"
                        pvValues.elCicdDefs.VOLUME_OBJ_NAMES << objName

                        volumeMap = [:]
                        volumeMap.VOLUME_MODE = pv.volumeMode
                        volumeMap.RECLAIM_POLICY = pv.reclaimPolicy
                        volumeMap.STORAGE_CAPACITY = pv.capacity
                        volumeMap.ACCESS_MODES = pv.accessModes ? pv.accessModes : [pv.accessMode]
                        volumeMap.VOLUME_NAMESPACE = namespace
                        volumeMap.VOLUME_TYPE = pv.volumeType
                        volumeMap.VOLUME_DEF = pv.volumeDef

                        pvValues["elCicdDefs-${objName}"] = volumeMap
                    }
                }
            }
        }
    }

    return pvValues
}

def getElCicdChartProjectPipelineValues(def projectInfo) {
    def pipelineValues = [elCicdDefs: [:]]
    def elCicdDefs = pipelineValues.elCicdDefs

    createElCicdProfiles(pipelineValues)

    if (projectInfo.artifacts) {
        pipelineValues.elCicdProfiles += 'hasArtifacts'
    }

    if (projectInfo.components) {
        pipelineValues.elCicdProfiles += 'hasComponents'
    }

    if (projectInfo.testComponents) {
        pipelineValues.elCicdProfiles += 'hasTestComponents'
    }

    getElCicdProjectCommonValues(projectInfo, elCicdDefs)

    getElCicdPipelineChartValues(projectInfo, elCicdDefs)

    projectInfo.modules.each { module ->
        pipelineValues["elCicdDefs-${module.name}"] = ['CODE_BASE' : module.codeBase ]
    }

    return pipelineValues
}

def getElCicdProjectCommonValues(def projectInfo, def elCicdDefs) {
    elCicdDefs.EL_CICD_MASTER_NAMESPACE = el.cicd.EL_CICD_MASTER_NAMESPACE
    elCicdDefs.PROJECT_ID = projectInfo.id

    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        elCicdDefs.NONPROD_ENVS = []
        elCicdDefs.NONPROD_ENVS.addAll(projectInfo.nonProdEnvs)
        
        elCicdDefs.SANDBOX_NAMESPACES = []
        elCicdDefs.SANDBOX_NAMESPACES.addAll(projectInfo.sandboxNamespaces.values())
    }

    if (el.cicd.EL_CICD_MASTER_PROD) {
        elCicdDefs.PROD_ENV = projectInfo.prodEnv
    }
}

def getElCicdPipelineChartValues(def projectInfo, def elCicdDefs) {
    elCicdDefs.TEAM_ID = projectInfo.teamInfo.id
    elCicdDefs.FOLDER_NAME = projectInfo.id
    elCicdDefs.GIT_BRANCH = projectInfo.gitBranch
    elCicdDefs.DEV_NAMESPACE = projectInfo.devNamespace
    elCicdDefs.EL_CICD_GIT_REPO = el.cicd.EL_CICD_GIT_REPO
    elCicdDefs.EL_CICD_GIT_REPO_BRANCH_NAME = el.cicd.EL_CICD_GIT_REPO_BRANCH_NAME
    elCicdDefs.EL_CICD_META_INFO_NAME = el.cicd.EL_CICD_META_INFO_NAME
    elCicdDefs.EL_CICD_BUILD_SECRETS_NAME = el.cicd.EL_CICD_BUILD_SECRETS_NAME
    elCicdDefs.DEV_ENV = projectInfo.devEnv

    elCicdDefs.BUILD_NAMESPACE_CHOICES = projectInfo.buildNamespaces.collect { "'${it}'" }.toString()

    elCicdDefs.REDEPLOY_ENV_CHOICES = projectInfo.testEnvs.collect { "'${it}'" }
    elCicdDefs.REDEPLOY_ENV_CHOICES.add("'${projectInfo.preProdEnv}'")
    elCicdDefs.REDEPLOY_ENV_CHOICES = elCicdDefs.REDEPLOY_ENV_CHOICES.toString()

    elCicdDefs.BUILD_ARTIFACT_PIPELINES = projectInfo.artifacts.collect { it.name }
    elCicdDefs.BUILD_COMPONENT_PIPELINES = projectInfo.components.collect { it.name }
    elCicdDefs.TEST_COMPONENT_PIPELINES = projectInfo.testComponents.collect { it.name }
    
    TEST_ENV_CHOICES = [:].keySet()
    TEST_ENV_CHOICES.addAll(projectInfo.nonProdEnvs.collect { env -> "'${env}'"  })
    TEST_ENV_CHOICES.addAll(projectInfo.sandboxEnvs.collect { env -> "'${env}'"  })
    elCicdDefs.TEST_ENV_CHOICES = TEST_ENV_CHOICES.toString()
}


def getElCicdChartProjectEnvironmentsValues(def projectInfo) {
    def environmentsValues = [elCicdDefs: [:]]
    def elCicdDefs = environmentsValues.elCicdDefs

    createElCicdProfiles(environmentsValues)

    getElCicdProjectCommonValues(projectInfo, elCicdDefs)

    getElCicdNamespaceChartValues(projectInfo, environmentsValues)

    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        elCicdDefs.NON_PROD_CICD_NAMESPACES = []
        
        elCicdDefs.NON_PROD_CICD_NAMESPACES = []
        elCicdDefs.NON_PROD_CICD_NAMESPACES += projectInfo.nonProdNamespaces.values()
        elCicdDefs.NON_PROD_CICD_NAMESPACES += projectInfo.sandboxNamespaces.values()
    }
    
    def rqProfiles = [:]
    elCicdDefs.CICD_NAMESPACES = []
    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        getElCicdNonProdEnvsResourceQuotasValues(projectInfo, elCicdDefs, rqProfiles)

        getElCicdRbacNonProdGroupsValues(projectInfo, elCicdDefs)

        elCicdDefs.CICD_NAMESPACES += projectInfo.nonProdNamespaces.values()
        elCicdDefs.CICD_NAMESPACES += projectInfo.sandboxNamespaces.values()
    }

    if (el.cicd.EL_CICD_MASTER_PROD) {
        getElCicdProdEnvsResourceQuotasValues(projectInfo, elCicdDefs, rqProfiles)

        getElCicdRbacProdGroupsValues(projectInfo, elCicdDefs)

        elCicdDefs.PROD_NAMESPACES = projectInfo.prodNamespaces.values()
        elCicdDefs.CICD_NAMESPACES += projectInfo.prodNamespaces.values()
    }
    environmentsValues.elCicdProfiles += rqProfiles.keySet()

    return environmentsValues
}

def getElCicdNamespaceChartValues(def projectInfo, def configValues) {
    def templateList = "elCicdTemplates-namespaces-cicd"
    configValues[templateList] = []
    
    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        def nsElCicdTemplate = [templateName: 'namespace', objNames: []]
        nsElCicdTemplate.objNames.addAll(projectInfo.nonProdNamespaces.values())
        if (projectInfo.sandboxNamespaces) {
            nsElCicdTemplate.objNames.addAll(projectInfo.sandboxNamespaces.values())
        }
        nsElCicdTemplate.annotations = ['elcicd.io/cicd-type': 'nonprod']
        
        configValues[templateList].add(nsElCicdTemplate)
    }

    if (el.cicd.EL_CICD_MASTER_PROD) {
        def nsElCicdTemplate = [templateName: 'namespace', objNames: []]
        nsElCicdTemplate.objNames.addAll(projectInfo.prodNamespaces.values())
        nsElCicdTemplate.annotations = ['elcicd.io/cicd-type': 'prod']
        
        configValues[templateList].add(nsElCicdTemplate)
    }
}

def getElCicdNonProdEnvsResourceQuotasValues(def projectInfo, def elCicdDefs, def rqProfiles) {
    def cicdEnvs = []
    cicdEnvs += projectInfo.nonProdEnvs
    cicdEnvs += projectInfo.sandboxEnvs

    cicdEnvs.each { env ->
        def rqNames = projectInfo.resourceQuotas[env]
        rqNames = !rqNames && !projectInfo.nonProdNamespaces[env] ? projectInfo.resourceQuotas[el.cicd.SANDBOX] : rqNames
        rqNames = rqNames ?: projectInfo.resourceQuotas[el.cicd.DEFAULT]
        rqNames?.each { rqName ->
            elCicdDefs["${rqName}_NAMESPACES"] = elCicdDefs["${rqName}_NAMESPACES"] ?: []
            elCicdDefs["${rqName}_NAMESPACES"] += (projectInfo.nonProdNamespaces[env] ?: projectInfo.sandboxNamespaces[env])
            rqProfiles[rqName] = 'placeHolder'
        }
    }
}

def getElCicdProdEnvsResourceQuotasValues(def projectInfo, def elCicdDefs, def rqProfiles) {
    cicdEnvs = [projectInfo.prodEnv]
    projectInfo.releaseProfiles.each { profile ->
        cicdEnvs.add("${projectInfo.prodEnv}-${profile}")
    }

    cicdEnvs.each { env ->
        def rqNames = projectInfo.resourceQuotas[env] ?: projectInfo.resourceQuotas[projectInfo.prodEnv]
        rqNames = rqNames ?: projectInfo.resourceQuotas[el.cicd.DEFAULT]
        rqNames?.each { rqName ->
            elCicdDefs["${rqName}_NAMESPACES"] = elCicdDefs["${rqName}_NAMESPACES"] ?: []
            elCicdDefs["${rqName}_NAMESPACES"] += projectInfo.prodNamespaces[env]
            rqProfiles[rqName] = 'placeHolder'
        }
    }
}

def getElCicdRbacNonProdGroupsValues(def projectInfo, def elCicdDefs) {
    cicdEnvs = []

    cicdEnvs += projectInfo.nonProdEnvs
    cicdEnvs += projectInfo.sandboxEnvs

    cicdEnvs.each { env ->
        def group = projectInfo.rbacGroups[env] ?: projectInfo.defaultRbacGroup
        def namespace = projectInfo.nonProdNamespaces[env] ?: projectInfo.sandboxNamespaces[env]
        elCicdDefs["${namespace}_GROUP"] = group
    }
}

def getElCicdRbacProdGroupsValues(def projectInfo, def elCicdDefs) {
    def group = projectInfo.rbacGroups[projectInfo.prodEnv] ?: projectInfo.defaultRbacGroup
    projectInfo.prodNamespaces.values().each { namespace ->
        elCicdDefs["${namespace}_GROUP"] = group
    }
}

def createProjectSshKeyValues(def projectInfo) {
    projectUtils.createModuleSshKeys(projectInfo.modules)
    
    configValues = [elCicdDefs: [:]]
    projectInfo.modules.each { module ->
        dir(module.workDir) {
            def sshKey = readFile(file: module.gitDeployKeyJenkinsId)

            configValues["elCicdDefs-${module.gitDeployKeyJenkinsId}"] = ['GIT_REPO_SSH_KEY': sshKey ]
        }
    }

    configValues.elCicdDefs.GIT_REPO_SSH_KEY_MODULE_IDS = projectInfo.modules.collect{ module ->
        if (!module.gitDeployKeyJenkinsId) {
            projectInfoUtils.setModuleScmDeployKeyJenkinsId(projectInfo, module)
        }
        return module.gitDeployKeyJenkinsId
    }

    return configValues
}

def createElCicdProfiles(def configValues) {
    configValues.elCicdProfiles = ['cicd']

    if (el.cicd.EL_CICD_MASTER_NONPROD) {
        configValues.elCicdProfiles += 'nonprod'
    }

    if (el.cicd.EL_CICD_MASTER_PROD) {
        configValues.elCicdProfiles += 'prod'
    }

    if (el.cicd.OKD_VERSION) {
        configValues.elCicdProfiles += 'okd'
    }
}