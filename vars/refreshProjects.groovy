/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 */

def call(Map args) {
    def includeTeams = args.includeTeams
    def includeProjects = args.includeProjects
    def refreshTeamServers = args.refreshTeamServers
    def refreshPipelines = args.refreshPipelines
    def refreshEnvironments = args.refreshEnvironments
    def refreshCredentials = args.refreshCredentials
    def confirmBeforeRefreshing = args.confirmBeforeRefreshing
    
    def projectRefreshMap
    stage('Gather all projects') {
        loggingUtils.echoBanner('GATHER LIST OF PROJECTS FOR REFRESH')
        
        projectRefreshMap = refreshProjectsUtils.getProjectRefreshMap(includeTeams, includeProjects)
    }
    
    stage('Confirm projects to be refreshed') {
        if (confirmBeforeRefreshing) {
            refreshProjectsUtils.confirmProjectsForRefresh(projectRefreshMap, args)
        }
        else {
            echo '--> USER CONFIRMATION NOT REQUESTED; SKIPPING'
        }
    }
    
    teamInfoList = []
    projectInfoList = []
    
    stage('Gather each projects information') {
        loggingUtils.echoBanner("GATHER INFORMATION FOR EACH PROJECTS TO BE REFRESHED")
        
        projectRefreshMap.each { teamId, projectList ->
            def teamInfo = projectInfoUtils.gatherTeamInfo(teamId)
            teamInfoList.add(teamInfo)
            projectInfoList += projectList.collect { projectId ->
                return projectInfoUtils.gatherProjectInfo(teamInfo, projectId)
            }
        }
    }
    
    refreshProjectsUtils.refreshProjectPipelines(projectInfoList, refreshPipelines)
    
    refreshProjectsUtils.refreshProjectSdlcEnvironments(projectInfoList, refreshEnvironments)
    
    refreshProjectsUtils.refreshProjectCredentials(projectInfoList, refreshCredentials)
    
    refreshProjectsUtils.refreshCredentials(projectInfoList, refreshCredentials)
    
    refreshProjectsUtils.refreshTeamCicdServers(teamInfoList, refreshTeamServers)
    
    loggingUtils.echoBanner('ALL TEAMS AND PROJECTS REFRESHED')

}
