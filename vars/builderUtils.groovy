/*
 * Basic build utilites and stages
 *
 * @see the build-to-dev pipeline for example on how to use
 */
 
def BUILDER = 'BUILDER' 
def TESTER = 'TESTER' 
def SCANNER = 'SCANNER' 

def loadBuilderModule(def microSerevice, def type) {
    def builderModule
    dir ("${el.cicd.EL_CICD_DIR}/builder-steps/${microService.codeBase}") {
        switch (type) {
            case BUILDER:
                def module = microService.builder ?: 'builder'
                builderModule = load "${module}.groovy"
            break
            case TESTER:
                def module = microService.tester ?: 'tester'
                builderModule = load "${module}.groovy"
            break
            case SCANNER:
                def module = microService.scanner ?: 'scanner'
                builderModule = load "${module}.groovy"
            break
        }
    }
    return builderModule
}

def buildTestAndScan(def projectInfo) {
    projectInfo.microServices.each { microService ->
        if (microService.gitBranch) { 
            stage("build step: build ${microService.name}") {
                pipelineUtils.echoBanner("BUILD MICROSERVICE: ${microService.name}")
        
                dir(microService.workDir) {
                    def builderModule = loadBuilderModule(microSerevice, BUILDER)
                    builderModules[BUILDER].build(projectInfo.id, microService.name)
                }
            }
        
            stage("build step: run unit tests for ${microService.name}") {
                pipelineUtils.echoBanner("RUN UNIT TESTS: ${microService.name}")
        
                dir(microService.workDir) {
                    def builderModule = loadBuilderModule(microSerevice, TESTER)
                    builderModules[TESTER].test(projectInfo.id, microService.name)
                }
            }
        
            stage("build step: source code analysis for ${microService.name}") {
                pipelineUtils.echoBanner("SCAN CODE: ${microService.name}")
        
                dir(microService.workDir) {
                    def builderModule = loadBuilderModule(microSerevice, SCANNER)
                    builderModules[SCANNER].scan(projectInfo.id, microService.name)
                }
            }
        }
    }
}