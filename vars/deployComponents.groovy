/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Deploys component into enviroment.
 */

def call(Map args) {
    def projectInfo = args.projectInfo
    def componentsToDeploy = args.componentsToDeploy ?: []
    def componentsToRemove = args.componentsToRemove ?: []

    if (!componentsToDeploy && !componentsToRemove) {
        loggingUtils.errorBanner('NO COMPONENTS TO DEPLOY OR REMOVE')
    }


    stage ('Clean up failed upgrades/installs') {
        loggingUtils.echoBanner('CLEAN UP ANY PREVIOUSLY FAILED UPGRADES/INSTALLS')
        deployComponentsUtils.cleanupFailedInstalls(projectInfo)
    }

    stage('Remove component(s)') {
        if (componentsToRemove) {            
            deployComponentsUtils.removeComponents(projectInfo, componentsToRemove)
        }
        else {
            loggingUtils.echoBanner('NO COMPONENTS TO REMOVE: SKIPPING')
        }
    }

    loggingUtils.echoBanner('SETUP COMPONENT(S) DEPLOYMENT DIRECTORY:', componentsToDeploy.collect { it.name }.join(', '))

    if (args.isBuild) {
        deployComponentsUtils.setupDeploymentDir(projectInfo, componentsToDeploy[0])
    }
    else {
        deployComponentsUtils.setupDeploymentDirs(projectInfo, componentsToDeploy)
    }

    loggingUtils.echoBanner('DEPLOY COMPONENT(S):', componentsToDeploy.collect { it.name }.join(', '))

    if (args.isBuild) {
        deployComponentsUtils.runComponentDeploymentStage(projectInfo, componentsToDeploy[0])
    }
    else {
        deployComponentsUtils.runComponentDeploymentStages(projectInfo, componentsToDeploy)
    }
    
    if (!componentsToDeploy) {
        echo '--> NO COMPONENTS TO DEPLOY: SKIPPING'
    }
    
    def componentsToTest = deployComponentsUtils.getTestComponents(projectInfo, componentsToDeploy)
    
    loggingUtils.echoBanner('RUNNING TEST COMPONENT(S):', componentsToTest.collect { it.name }.join(', '))
    
    deployComponentsUtils.runTestComponents(projectInfo, componentsToTest)
    
    stage('Summary') {
        componentsToRemove.each { it.flaggedForRemoval = true }
        componentsToDeploy.each { it.flaggedForDeployment = true }
        componentsToTest.each { it.flaggedForTest }
        
        
        deployComponentsUtils.outputDeploymentSummary(projectInfo)
    }
}