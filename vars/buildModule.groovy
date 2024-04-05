/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

void call(Map args) {
    def projectInfo = args.projectInfo
    def module = projectInfo.modules.find { it.name == args.moduleName }

    module.gitBranch = args.gitBranch

    projectInfo.deployToEnv = projectInfo.devEnv
    projectInfo.deployToNamespace = args.deployToNamespace

    stage('Checkout code from repository') {
        moduleUtils.cloneModule(module)
    }
    
    def moduleType = module.isArtifact ? 'ARTIFACT' : 'COMPONENT' 
    [el.cicd.BUILDER, el.cicd.TESTER, el.cicd.ANALYZER, el.cicd.ASSEMBLER].each { buildStep ->
        stage("Build step: ${buildStep.toUpperCase()}") {
            moduleUtils.runBuildStep(projectInfo, module, buildStep, moduleType)
        }
    }

    if (!module.isArtifact) {
        stage('build, scan, and push image to repository') {
            moduleUtils.buildScanAndPushImage(projectInfo, module)
        }
        
        deployComponents(projectInfo: projectInfo,
                         componentsToDeploy: [module],
                         imageTag: module.imageTag,
                         recreate: args.recreate,
                         isBuild: true)
    }
}