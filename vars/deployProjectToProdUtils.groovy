/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply deploying to production
 */

def selectReleaseVersion(def projectInfo, def args) {
    dir(projectInfo.projectModule.workDir) {
        def forEachrefScript =
            "git for-each-ref --count=5 --format='%(refname:short)' --sort='-refname' 'refs/remotes/origin/*'"
        

        def releaseVersions =
            sh(returnStdout: true, script: "${forEachrefScript} | grep -E '${projectInfoUtils.SEMVER_REGEX}'").
                split(/\s+/).
                sort()
    
    
        def inputs = [choice(name: 'releaseVersion', description: "Release version of ${projectInfo.id} to deploy", choices: releaseVersions),
                      string(name: 'variant', description: 'Variant of release version [optional]', trim: true),
                      booleanParam(name: 'cleanNamespace', description: 'Uninstall the currently deployed version of the project first')]
                        
        jenkinsUtils.displayInputWithTimeout("Select release version of ${projectInfo.id} to deploy", args, inputs)
    }
}
