/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def getSelectedModules(def projectInfo, def args) {
    def DEPLOY_TO_NAMESPACE = 'Deploy to Namespace'
    def GIT_BRANCH = 'Git branch?'
    def CLEAN_NAMESPACE = 'Clean namespace?'

    def cnDesc = 'Uninstall all components currently deployed in selected namespace before deploying new builds'
    List inputs = [choice(name: DEPLOY_TO_NAMESPACE,
                          description: 'The namespace to build and deploy to',
                          choices: projectInfo.buildNamespaces),
                   stringParam(name: GIT_BRANCH, defaultValue: projectInfo.gitBranch, description: 'Branch to build?'),
                   booleanParam(name: CLEAN_NAMESPACE, description: cnDesc)]

    def BUILD_ALL_ARTIFACTS = 'Build all artifacts'
    def BUILD_ALL_COMPONENTS = 'Build and deploy all components'

    inputs += separator(name: 'BULK BUILD OPTIONS', sectionHeader: 'BULK BUILD OPTIONS')
    inputs += booleanParam(name: BUILD_ALL_ARTIFACTS)
    inputs += booleanParam(name: BUILD_ALL_COMPONENTS)

    inputs += separator(name: 'ARTIFACTS', sectionHeader: 'ARTIFACTS')
    createModuleInputs(inputs, projectInfo, projectInfo.artifacts, 'Artifact')

    inputs += separator(name: 'COMPONENTS', sectionHeader: 'COMPONENTS')
    createModuleInputs(inputs, projectInfo, projectInfo.components, 'Component')

    def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select artifacts and components to build:", args, inputs)

    projectInfo.deployToNamespace = cicdInfo[DEPLOY_TO_NAMESPACE]
    projectInfo.gitBranch = cicdInfo[GIT_BRANCH]
    projectInfo.cleanNamespace = cicdInfo[CLEAN_NAMESPACE]

    projectInfo.selectedArtifacts = projectInfo.artifacts.findAll { cicdInfo[BUILD_ALL_ARTIFACTS] || cicdInfo[it.name] }
    projectInfo.selectedComponents = projectInfo.components.findAll { cicdInfo[BUILD_ALL_COMPONENTS] || cicdInfo[it.name] }
}

def getSelectedTestModules(def projectInfo, def args) {
    def GIT_BRANCH = 'Git branch?'

    List inputs = [stringParam(name: GIT_BRANCH, defaultValue: projectInfo.gitBranch, description: 'Branch to build?')]

    def RUN_ALL_TEST_COMPONENTS = 'Run all test components'

    inputs += separator(name: 'BULK TEST OPTIONS', sectionHeader: 'BULK TEST OPTIONS')
    inputs += booleanParam(name: RUN_ALL_TEST_COMPONENTS)

    inputs += separator(name: 'TEST COMPONENTS', sectionHeader: 'TEST COMPONENTS')
    createModuleInputs(inputs, projectInfo, projectInfo.testComponents, 'Test Component')

    def cicdInfo = jenkinsUtils.displayInputWithTimeout("Select test components to run in ${args.agentNamespace}:", args, inputs)

    projectInfo.gitBranch = cicdInfo[GIT_BRANCH]

    projectInfo.selectedTestComponents = projectInfo.testComponents.findAll { cicdInfo[RUN_ALL_TEST_COMPONENTS] || cicdInfo[it.name] }
}
 
 def cloneModule(def module) {
    loggingUtils.echoBanner("CLONING ${module.gitRepoName} REPO, REFERENCE: ${module.projectInfo.gitBranch}")

    projectInfoUtils.cloneGitRepo(module, module.projectInfo.gitBranch) {
        sh """
            ${shCmd.echo 'filesChanged:'}
            git diff HEAD^ HEAD --stat 2> /dev/null || :
        """

        module.srcCommitHash = sh(returnStdout: true, script: "git rev-parse --short HEAD | tr -d '[:space:]'")
    }
}

def runBuildStep(def projectInfo, def module, def buildStep, def moduleType) {
    def buildStepName = module[buildStep] ?: buildStep
    loggingUtils.echoBanner("RUN ${buildStepName.toUpperCase()} FOR ${moduleType}: ${module.name}")

    dir(module.workDir) {
        def pipelineBuildStepFile = getPipelineBuildStepFile(module, buildStepName)
        def builderModule = load(pipelineBuildStepFile)
        switch(buildStep) {
            case el.cicd.BUILDER:
                builderModule.build(projectInfo, module)
                break;
            case el.cicd.TESTER:
                builderModule.test(projectInfo, module)
                break;
            case el.cicd.ANALYZER:
                builderModule.analyze(projectInfo, module)
                break;
            case el.cicd.ASSEMBLER:
                builderModule.assemble(projectInfo, module)
                break;
            case el.cicd.LINTER:
                builderModule.lint(projectInfo, module)
                break;
        }
    }
}

def buildScanAndPushImage(def projectInfo, def module) {
    module.imageTag = projectInfo.deployToNamespace - "${projectInfo.id}-"
    loggingUtils.echoBanner("BUILDING ${module.id}:${module.imageTag} IMAGE")

    def imageRepo = el.cicd["${projectInfo.DEV_ENV}${el.cicd.OCI_REGISTRY_POSTFIX}"]

    def tlsVerify = el.cicd.DEV_OCI_REGISTRY_ENABLE_TLS ? "--tls-verify=${el.cicd.DEV_OCI_REGISTRY_ENABLE_TLS}" : ''

    withCredentials([usernamePassword(credentialsId: jenkinsUtils.getImageRegistryCredentialsId(projectInfo.devEnv),
                    usernameVariable: 'DEV_OCI_REGISTRY_USERNAME',
                    passwordVariable: 'DEV_OCI_REGISTRY_PWD')]) {
        dir(module.workDir) {
            sh """
                chmod 777 Dockerfile

                echo "\nLABEL SRC_COMMIT_REPO='${module.gitRepoUrl}'" >> Dockerfile
                echo "\nLABEL SRC_COMMIT_BRANCH='${module.gitBranch}'" >> Dockerfile
                echo "\nLABEL SRC_COMMIT_HASH='${module.srcCommitHash}'" >> Dockerfile
                echo "\nLABEL EL_CICD_BUILD_TIME='\$(date +%d.%m.%Y-%H.%M.%S%Z)'" >> Dockerfile

                podman login ${tlsVerify} --username \${DEV_OCI_REGISTRY_USERNAME} --password \${DEV_OCI_REGISTRY_PWD} ${imageRepo}

                podman build --build-arg=EL_CICD_BUILD_SECRETS_NAME=./${el.cicd.EL_CICD_BUILD_SECRETS_NAME} --squash \
                            -t ${imageRepo}/${module.id}:${module.imageTag} -f ./Dockerfile
            """

            loggingUtils.echoBanner("SCAN ${module.id}:${module.imageTag} IMAGE")

            def buildStepName = module[el.cicd.SCANNER] ?: el.cicd.SCANNER
            def pipelineBuildStepFile = getPipelineBuildStepFile(module, buildStepName)
            def imageScanner = load(pipelineBuildStepFile)
            imageScanner.scanImage(projectInfo, module.name)

            loggingUtils.echoBanner("PUSH ${module.id}:${module.imageTag} IMAGE")

            sh """
                podman push ${tlsVerify} ${imageRepo}/${module.id}:${module.imageTag}
            """
        }
    }
}

def getPipelineBuildStepFile(def module, def buildStepName) {
    def pipelineBuildStepFile = "${el.cicd.BUILDER_STEPS_DIR}/${module.codeBase}/${buildStepName}.groovy"
    return fileExists(pipelineBuildStepFile) ?
        pipelineBuildStepFile :  "${el.cicd.BUILDER_STEPS_DIR}/${buildStepName}.groovy"
}

def runSelectedModulePipeline(def projectInfo, def module, def title, def params = []) {
    if (module) {
        loggingUtils.echoBanner("RUNNING SELECTED ${title.toUpperCase()} PIPELINE")
        
        echo "--> Running ${module.isTestComponent ? 'test' : 'build'} ${module.name}"

        pipelineSuffix = module.isArtifact ? el.cicd.BUILD_ARTIFACT_PIPELINE_SUFFIX : 
            (module.isComponent ? el.cicd.BUILD_COMPONENT_PIPELINE_SUFFIX : el.cicd.RUN_TEST_COMPONENT_PIPELINE_SUFFIX)
        build(job: "${projectInfo.id}-${module.name}-${pipelineSuffix}", wait: true, parameters: params)

        echo "--> ${module.name} ${module.isTestComponent ? 'test(s)' : 'build'} complete"
    
        loggingUtils.echoBanner("SELECTED ${title.toUpperCase()} PIPELINES COMPLETE")
    }
    else {
        loggingUtils.echoBanner("NO ${title.toUpperCase()} PIPELINE SELECTED: SKIPPING")
    }
}

def runSelectedModulePipelines(def projectInfo, def modules, def title, def params = []) {
    if (modules) {
        loggingUtils.echoBanner("RUNNING SELECTED ${title.toUpperCase()} PIPELINES")

        concurrentUtils.runParallelStages("Run ${title} pipelines", modules) { module ->
            echo "--> Running ${module.isTestComponent ? 'test' : 'build'} ${module.name}"

            pipelineSuffix = module.isArtifact ? el.cicd.BUILD_ARTIFACT_PIPELINE_SUFFIX : 
                (module.isComponent ? el.cicd.BUILD_COMPONENT_PIPELINE_SUFFIX : el.cicd.RUN_TEST_COMPONENT_PIPELINE_SUFFIX)
            build(job: "${projectInfo.id}-${module.name}-${pipelineSuffix}", wait: true, parameters: params)

            echo "--> ${module.name} ${module.isTestComponent ? 'test(s)' : 'build'} complete"
        }

        loggingUtils.echoBanner("SELECTED ${title.toUpperCase()} PIPELINES COMPLETE")
    }
    else {
        loggingUtils.echoBanner("NO ${title.toUpperCase()} PIPELINES SELECTED: SKIPPING")
    }
}

def createModuleInputs(def inputs, def projectInfo, def modules, def allTitle) {
    modules.each { module ->
        inputs.add(booleanParam(name: module.name, description: "Build ${module.name}?  Status: ${module.status}"))
    }
}