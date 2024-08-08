/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * General pipeline utilities
 */
 
import groovy.transform.Field

@Field
SEMVER_REGEX = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(?:-((?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\.(?:0|[1-9]\d*|\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\+([0-9a-zA-Z-]+(?:\.[0-9a-zA-Z-]+)*))?$/
 
def gatherTeamInfo(def teamId) {
    assert teamId : "teamId (${teamId}) cannot be empty"
    
    return [id: teamId, cicdMasterNamespace: "${teamId}-${el.cicd.EL_CICD_MASTER_NAMESPACE}"]
}

def gatherProjectInfoStage(def teamInfo, def projectId) {
    def projectInfo
    stage('Gather project information') {
        loggingUtils.echoBanner("GATHER INFORMATION FOR PROJECT ${projectId} IN TEAM ${teamInfo.id}")

        projectInfo = gatherProjectInfo(teamInfo, projectId)
    }

    return projectInfo
}

def gatherProjectInfo(def teamInfo, def projectId) {
    assert teamInfo && projectId : "teamInfo (${teamInfo?.id}) and (${projectId}) cannot be empty"

    def projectInfo = readProjectYaml(teamInfo, projectId)
    
    projectInfo.teamInfo = teamInfo
    projectInfo.id = projectId
    
    projectInfo.jenkinsHostUrl =
        "https://${projectInfo.teamInfo.cicdMasterNamespace}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}"

    projectInfo.repoDeployKeyId =
        "${projectInfo.teamInfo.cicdMasterNamespace}|${projectInfo.id}|${el.cicd.EL_CICD_CLUSTER_UID}"

    initProjectModuleData(projectInfo)

    initProjectEnvNamespaceData(projectInfo)

    initProjectSandboxData(projectInfo)

    projectInfo.resourceQuotas = projectInfo.resourceQuotas ?: [:]
    projectInfo.staticPvs = projectInfo.staticPvs ?: []

    projectInfo.defaultRbacGroup = projectInfo.rbacGroups[el.cicd.DEFAULT] ?: projectInfo.rbacGroups[projectInfo.devEnv]

    validateProjectInfo(projectInfo)

    return projectInfo
}

def readProjectYaml(def teamInfo, def projectId) {
    def projectInfo
    dir (el.cicd.PROJECT_DEFS_DIR) {
        def projectFile = findFiles(glob: "**/${teamInfo.id}/${projectId}.yaml")
        projectFile = projectFile ?: findFiles(glob: "**/${projectId}.yml")
        projectFile = projectFile ?: findFiles(glob: "**/${projectId}.json")

        if (projectFile) {
            projectInfo = readYaml file: projectFile[0].path
        }
        else {
            loggingUtils.errorBanner("PROJECT NOT FOUND: ${teamInfo.id}/${projectId}")
        }
    }

    return projectInfo
}

def initProjectModuleData(def projectInfo) {
    projectInfo.workDir =  "${WORKSPACE}/${projectInfo.id}"

    projectInfo.components = projectInfo.components ?: []
    projectInfo.deploymentDirs = [:]
    projectInfo.components.each { component ->
        setComponentData(projectInfo, component)
        component.isComponent = true
    }

    projectInfo.artifacts = projectInfo.artifacts ?: []
    projectInfo.artifacts.each { artifact ->
        setModuleData(projectInfo, artifact)
        artifact.isArtifact = true
    }

    projectInfo.testComponents = projectInfo.testComponents ?: []
    projectInfo.testComponents.each { testComponent ->
        setComponentData(projectInfo, testComponent)
        testComponent.isTestComponent = true
    }
    
    projectInfo.buildModules = []
    projectInfo.buildModules.addAll(projectInfo.components)
    projectInfo.buildModules.addAll(projectInfo.artifacts)

    projectInfo.modules = []
    projectInfo.modules.addAll(projectInfo.components)
    projectInfo.modules.addAll(projectInfo.artifacts)
    projectInfo.modules.addAll(projectInfo.testComponents)
    
    if (el.cicd.EL_CICD_MASTER_PROD.toBoolean()) {
        createProjectModule(projectInfo)
        projectInfo.modules.add(projectInfo.projectModule)
    }
}

def setComponentData(def projectInfo, def component) {
    setModuleData(projectInfo, component)
    
    component.deploymentDir = "${component.workDir}/${el.cicd.CHART_DEPLOY_DIR}"
    projectInfo.deploymentDirs[component.name] = "${projectInfo.workDir}/${component.name}"
    
    component.staticPvs = component.staticPvs ?: []
}

def setModuleData(def projectInfo, def module) {
    module.projectInfo = projectInfo

    module.name = module.gitRepoName.toLowerCase().replaceAll(/[^-0-9a-z]/, '-')
    module.id = "${projectInfo.id}-${module.name}"

    module.workDir = "${WORKSPACE}/${module.gitRepoName}"

    module.gitRepoUrl = "git@${projectInfo.gitHost}:${projectInfo.gitOrganization}/${module.gitRepoName}.git"
    module.gitBranch = projectInfo.gitBranch
    module.gitDeployKeyJenkinsId = "${projectInfo.id}-${module.name}-${el.cicd.GIT_CREDS_POSTFIX}"
}

def createProjectModule(def projectInfo) {
    projectInfo.projectModule = [gitRepoName: projectInfo.id]
    setModuleData(projectInfo, projectInfo.projectModule)
    projectInfo.projectModule.gitDeployKeyJenkinsId = "${projectInfo.id}-${el.cicd.GIT_CREDS_POSTFIX}"
}

def initProjectEnvNamespaceData(def projectInfo) {
    projectInfo.devEnv = el.cicd.devEnv

    projectInfo.hotfixEnv = el.cicd.hotfixEnv

    projectInfo.testEnvs = (el.cicd.TEST_ENVS && projectInfo.enabledTestEnvs) ?
        el.cicd.TEST_ENVS.findAll { projectInfo.enabledTestEnvs.contains(it) } : []

    projectInfo.preProdEnv = el.cicd.preProdEnv
    projectInfo.prodEnv = el.cicd.prodEnv

    projectInfo.nonProdEnvs = [projectInfo.devEnv]
    if (projectInfo.allowsHotfixes) {
        projectInfo.nonProdEnvs << projectInfo.hotfixEnv
    }
    projectInfo.nonProdEnvs.addAll(projectInfo.testEnvs)
    projectInfo.nonProdEnvs.add(projectInfo.preProdEnv)

    projectInfo.DEV_ENV = el.cicd.DEV_ENV
    projectInfo.HOTFIX_ENV = el.cicd.HOTFIX_ENV
    projectInfo.PRE_PROD_ENV = el.cicd.PRE_PROD_ENV
    projectInfo.PROD_ENV = el.cicd.PROD_ENV

    projectInfo.TEST_ENVS = projectInfo.testEnvs.collect { it.toUpperCase() }
    projectInfo.NON_PROD_ENVS = projectInfo.nonProdEnvs.collect { it.toUpperCase() }

    projectInfo.devNamespace = "${projectInfo.id}-${projectInfo.devEnv}"
    projectInfo.preProdNamespace = "${projectInfo.id}-${projectInfo.preProdEnv}"

    projectInfo.hotfixNamespace = "${projectInfo.id}-${projectInfo.hotfixEnv}"

    projectInfo.nonProdNamespaces = [(projectInfo.devEnv): projectInfo.devNamespace]
    if (projectInfo.allowsHotfixes) {
        projectInfo.nonProdNamespaces[projectInfo.hotfixEnv] = projectInfo.hotfixNamespace
    }
    
    projectInfo.releaseProfiles = projectInfo.releaseProfiles ?: []
    projectInfo.prodNamespaces = [(projectInfo.prodEnv): "${projectInfo.id}-${projectInfo.prodEnv}"]
    projectInfo.releaseProfiles.each { profile ->
        projectInfo.prodNamespaces["${projectInfo.prodEnv}-${profile}"] = "${projectInfo.id}-${projectInfo.prodEnv}-${profile}"
    }

    projectInfo.testEnvs.each { env ->
        projectInfo.nonProdNamespaces[env] = "${projectInfo.id}-${env}"
    }
    projectInfo.nonProdNamespaces[projectInfo.preProdEnv] = projectInfo.preProdNamespace

    projectInfo.buildNamespaces = [projectInfo.devNamespace]
    projectInfo.allowsHotfixes && projectInfo.buildNamespaces << projectInfo.hotfixNamespace
}

def initProjectSandboxData(def projectInfo) {
    def sandboxes = projectInfo.sandboxEnvs ?: 0
    projectInfo.sandboxEnvs = []
    projectInfo.sandboxNamespaces = [:]
    if (sandboxes) {
        (1..sandboxes).each { i ->
            def sandboxEnv = "${el.cicd.SANDBOX_NAMESPACE_PREFIX}-${i}"
            projectInfo.sandboxEnvs << sandboxEnv
            projectInfo.sandboxNamespaces[sandboxEnv] = "${projectInfo.id}-${sandboxEnv}"
        }

        projectInfo.buildNamespaces.addAll(projectInfo.sandboxNamespaces.values())
    }
}

def validateProjectInfo(def projectInfo) {
    assert projectInfo.rbacGroups : 'missing rbacGroups'

    def errMsg = "missing ${projectInfo.devEnv} rbacGroup: this is the default RBAC group for all environments if not otherwise specified"
    assert projectInfo.defaultRbacGroup : errMsg

    assert projectInfo.gitHost ==~
        /^(([a-zA-Z0-9]|[a-zA-Z0-9][a-zA-Z0-9-]*[a-zA-Z0-9])\.)*([A-Za-z0-9]|[A-Za-z0-9][A-Za-z0-9\-]*[A-Za-z0-9])$/ :
        "bad or missing gitHost '${projectInfo.gitHost}"
    assert projectInfo.gitRestApiHost ==~
        /^(([\p{Alnum}]|[\p{Alnum}][\p{Alnum}-]*[\p{Alnum}])\.)*([\p{Alnum}]|[\p{Alnum}][\p{Alnum}-]*[\p{Alnum}])(\/[\p{Alnum}][\p{Alnum}-]*)*$/ :
        "bad or missing gitRestApiHost '${projectInfo.gitHost}"
    assert projectInfo.gitOrganization : "missing gitOrganization"
    assert projectInfo.gitBranch : "missing git branch"
    assert ((projectInfo.modules.size() - projectInfo.testComponents.size()) > 0) : "No components or artifacts defined"

    projectInfo.buildModules.each { buildModule ->
        assert buildModule.gitRepoName ==~ /[\w-.]+/ : "bad git repo name for component, [\\w-.]+: ${buildModule.gitRepoName}"
        assert buildModule.codeBase ==~ /[a-z][a-z0-9-]+/ : "bad codeBase name, [a-z-][a-z0-9-]+: ${buildModule.codeBase}"
    }

    projectInfo.testComponents.each { testComponent ->
        assert testComponent.gitRepoName ==~ /[\w-.]+/ : "bad git repo name for component, [\\w-.]+: ${testComponent.gitRepoName}"
        assert testComponent.codeBase ==~ /[a-z][a-z0-9-]+/ : "bad codeBase name, [a-z-][a-z0-9-]+: ${testComponent.codeBase}"
    }

    projectInfo.enabledTestEnvs.each { env ->
        assert el.cicd.TEST_ENVS.contains(env) : "test environment '${env}' must be in [${el.cicd.testEnvs}]"
    }

    validateProjectPvs(projectInfo)
}

def validateProjectPvs(def projectInfo) {
    pvMap = [:]
    projectInfo.staticPvs.each { pv ->
        assert pv.envs : "missing persistent volume environments"
        pv.envs.each { env ->
            assert projectInfo.NON_PROD_ENVS.contains(env) || env == projectInfo.PROD_ENV
        }

        assert pv.capacity ==~ /\d{1,4}(Mi|Gi)/ : "pv.capacity missing or invalid format \\d{1,4}(Mi|Gi): '${pv.capacity}'"
        assert pv.accessMode ==~
            /(ReadWriteOnce|ReadWriteMany|ReadOnly)/ :
            "missing or invalid pv.accessMode (ReadWriteOnce|ReadWriteMany|ReadOnly): '${pv.accessMode}'"
        assert pv.volumeType ==~ /\w+/ : "missing volume type, pv.volumeType: '${pv.volumeType}'"
        assert pv.volumeDef : "missing volume definition, pv.volumeDef"
        
        def msg = "each project static volume must have a unique name: ${pv.name ? pv.name : '<missing name>'}"
        assert (pv.name && !pvMap[pv.name]): msg
        pvMap[pv.name] = true
    }
}

def cloneGitRepo(def module, def gitReference = null, Closure postProcessing = null) {
    gitReference = gitReference ?: 'origin/*'
    dir (module.workDir) {
        checkout scmGit(
            branches: [[ name: gitReference ]],
            userRemoteConfigs: [
                [ 
                    url: module.gitRepoUrl,
                    credentialsId: module.gitDeployKeyJenkinsId
                ]
            ]
        )
        
        if (postProcessing) {
            withCredentials([sshUserPrivateKey(credentialsId: module.gitDeployKeyJenkinsId, keyFileVariable: 'GITHUB_PRIVATE_KEY')]) {
                postProcessing?.call(module)
            }            
        }
    }
}

def getNonProdDeploymentBranchName (def projectInfo, def component, def deploymentEnv) {
    return (projectInfo.testEnvs.contains(deploymentEnv) || deploymentEnv == el.cicd.preProdEnv) ?
        "${el.cicd.DEPLOYMENT_BRANCH_PREFIX}-${deploymentEnv}-${component.srcCommitHash}" : ''
}