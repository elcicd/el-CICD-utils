/* 
 * SPDX-License-Identifier: LGPL-2.1-or-later
 *
 * Utility methods for apply OCP resources
 *
 * @see the projectid-onboard pipeline for example on how to use
 */

import groovy.transform.Field

@Field
def UNDEFINED = 'undefined'

@Field
def OKD_CONFIG_DIR = '.openshift'

def mergeMaps(def toMap, def fromMap) {
    if (toMap && fromMap) {
        fromMap.each { k, v -> toMap[k] = toMap[k] in Map ? mapMerge(toMap[k], v) : v }
    }

    return (toMap ?: fromMap)
}

def readTemplateDefs() {
    def templateDefs = findFiles(glob: "**/template-defs.json")
    templateDefs = templateDefs ?: findFiles(glob: "**/template-defs.yml")
    templateDefs = templateDefs ?: findFiles(glob: "**/template-defs.yaml")

    if (templateDefs) {
        templateDefs = templateDefs[0].path
        try {
            templateDefs = readYaml file: templateDefs
        }
        catch (Exception e) {
            templateDefs = readJSON file: templateDefs
        }
    }
    else {
        errorBanner("TEMPLATE-DEFS NOT FOUND: must be named templateDefs.json/yaml/yml and be legitimate JSON or YAML")
    }

    return templateDefs
}

def processTemplateDefs(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    pipelineUtils.echoBanner("BUILD TEMPLATES AND RETRIEVE TEMPLATE DEFINITIONS")

    writeFile file:"${el.cicd.TEMPLATES_DIR}/kustomization-template.yml", text: libraryResource('templates/kustomization-template.yml')

    microServices.each { microService ->
        dir("${microService.workDir}/${OKD_CONFIG_DIR}") {
            microService.templateDefs = readTemplateDefs()

            if (microService.templateDefs.templates) {
                microService.templateDefs.templates.eachWithIndex { templateDef, index ->
                    templateDef.appName = templateDef.appName ?: microService.name
                    templateDef.patchedFile = "patched-${templateDef.appName}-${index}.yml".toString()

                    kustomizeTemplate(projectInfo, templateDef, index)

                    templateDef.params = mergeMaps(templateDef.params, templateDef[projectInfo.deployToEnv]?.params)
                }
            }
            else {
                ${shellEcho "No OpenShift templates found"}
            }
        }
    }
}

def kustomizeTemplate(def projectInfo, def templateDef, def index) {
    def templateFileName = templateDef.file ?: "${templateDef.templateName}.yml"
    def templateFile = templateDef.file ?: "${el.cicd.OKD_TEMPLATES_DIR}/${templateFileName}"
    def envPatchFile = templateDef[projectInfo.deployToEnv]?.patchFile ?: templateDef.patchFile
    if (envPatchFile) {
        def envPatchFileName = envPatchFile.split('/').last()
        def tempKustomizeDir = './kustomize-tmp'
        sh """
            ${shellEcho "Kustomizing ${templateDef.templateName} to ${templateDef.patchedFile} with patch: ${envPatchFile}" }
            mkdir -p ${tempKustomizeDir}
            cp "${templateFile}" ${tempKustomizeDir}

            cp ${envPatchFile} ${tempKustomizeDir}

            cat ${el.cicd.TEMPLATES_DIR}/kustomization-template.yml | \
                sed -e 's|%TEMPLATE_FILE%|${templateFileName}|; s|%TEMPLATE_NAME%|${templateDef.templateName}|; s|%PATCH_FILE%|${envPatchFileName}|' > ${tempKustomizeDir}/kustomization.yml

            kustomize build ${tempKustomizeDir} > ${templateDef.patchedFile}

            cat ${templateDef.patchedFile}
            rm -rf ${tempKustomizeDir}
        """
    }
    else {
        sh """
            echo
            ${shellEcho "No kustomize patch defined for:",
                        "  templateDef #${index}: ${templateFileName}",
                        "  appName: ${templateDef.appName}",
                        "  template file: ${templateFile}"}
            cat ${templateFile} > ${templateDef.patchedFile}
        """
    }
}

def processTemplates(def projectInfo, def microServices, def imageTag) {
    assert projectInfo; assert microServices; assert imageTag

    pipelineUtils.echoBanner("APPLY OKD TEMPLATES AND RESOURCES")

    microServices.each { microService ->
        if (microService.templateDefs) {
            dir("${microService.workDir}/${OKD_CONFIG_DIR}") {
                microService.templateDefs.templates.eachWithIndex { templateDef, index ->
                    if (templateDef.params) {
                        if (!templateDef.params.ROUTE_HOST) {
                            def postfix = (projectInfo.deployToEnv != projectInfo.prodEnv) ? 
                                (projectInfo.deployToNamespace - projectInfo.id) : ''

                            templateDef.params.ROUTE_HOST = "${templateDef.appName}${postfix}.${el.cicd.CLUSTER_WILDCARD_DOMAIN}".toString()
                        }
                    }

                    def paramsStr = templateDef.params.collect { key, value -> "-p '${key}=${value}'" }.join(' ')

                    def ENV_TO = projectInfo.deployToEnv.toUpperCase()
                    def imageRepository = el.cicd["${ENV_TO}${el.cicd.IMAGE_REPO_POSTFIX}"]
                    def pullSecret = el.cicd["${ENV_TO}${el.cicd.IMAGE_REPO_PULL_SECRET_POSTFIX}"]

                    def fileName = templateDef.file ?: "${templateDef.templateName}.yml"
                    sh """
                        ${shellEcho '******',
                                    'PROCESSED AND APPLYING OCP TEMPLATE ' + templateDef.patchedFile,
                                    'PARAMS: ' + paramsStr,
                                    '******' }
                        mkdir -p ${projectInfo.deployToEnv}
                        oc process --local --ignore-unknown-parameters \
                            ${paramsStr} \
                            -p 'PROJECT_ID=${projectInfo.id}' \
                            -p 'MICROSERVICE_NAME=${microService.name}' \
                            -p 'APP_NAME=${templateDef.appName}' \
                            -p 'IMAGE_REPOSITORY=${imageRepository}' \
                            -p 'PULL_SECRET=${pullSecret}' \
                            -p 'ENV=${projectInfo.deployToEnv}' \
                            -p 'IMAGE_TAG=${imageTag}' \
                            -f ${templateDef.patchedFile} \
                            -o yaml > ./${projectInfo.deployToEnv}/processed-${index}-${fileName}
                    """
                }
            }
        }
        else {
            ${shellEcho 'No OpenShift deployment resource(s) found'}
        }
    }
}

def applyResources(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    microServices.each { microService ->
        dir("${microService.workDir}/${OKD_CONFIG_DIR}") {
            sh """
                mkdir -p default
                cp -n -v default/* ${projectInfo.deployToEnv} 2> /dev/null || ${shellEcho "No default OCP resources found"}

                cd ${projectInfo.deployToEnv}
                if [[ \$(ls *.{yml,yaml,json} 2> /dev/null | wc -l) -gt 0 ]]
                then
                    ${shellEcho '',
                                '******',
                                "APPLYING OCP RESOURCES FOR ${microService.name} IN PROJECT ${projectInfo.id}"}
                    IMAGE_PULL_BACKOFF_PODS=\$(oc get pods --no-headers -n ${projectInfo.deployToNamespace} | grep "${microService.name}-.*" | grep -i 'ImagePull')||:
                    if [[ ! -z "\${IMAGE_PULL_BACKOFF_PODS}" ]]
                    then
                        oc delete cronjob -l microservice=${microService.name} -n ${projectInfo.deployToNamespace}
                    fi

                    oc delete --cascade=false --wait dc -l microservice=${microService.name} -n ${projectInfo.deployToNamespace}
                    oc apply --overwrite --recursive -f . -n ${projectInfo.deployToNamespace}
                    ${shellEcho '******'}

                    ${shellEcho '',
                                '******',
                                "LABELING OCP RESOURCES FOR ${microService.name} IN PROJECT ${projectInfo.id}"}
                    oc label --overwrite --recursive -f . \
                        projectid=${projectInfo.id} \
                        microservice=${microService.name} \
                        deployment-commit-hash=${microService.deploymentCommitHash} \
                        release-version=${projectInfo.releaseVersionTag ?: UNDEFINED} \
                        deploy-time=\$(date +%d.%m.%Y-%H.%M.%S%Z) \
                        build-number=${BUILD_NUMBER} \
                        -n ${projectInfo.deployToNamespace}
                    ${shellEcho   '******'}
                else
                    ${shellEcho  'No OpenShift deployment resource(s) found'}
                fi
            """
        }
    }
}

def rolloutLatest(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    def microServiceNames = microServices.collect { microService -> microService.name }.join(' ')
    sh """
        ${pipelineUtils.shellEchoBanner("CLEANUP EXISTING DEPLOYMENTS FOR MICROSERVICES ${projectInfo.deployToNamespace}:", "${microServiceNames}")}

        DCS="\$(oc get dc --ignore-not-found -l build-number=\${BUILD_NUMBER} -o 'custom-columns=:.metadata.name' -n ${projectInfo.deployToNamespace} | xargs)"

        FOR_DELETE_DCS=\$(echo \${DCS} | tr ' ' '|')
        FOR_DELETE_PODS=\$(oc get pods  -o 'custom-columns=:.metadata.name' -n ${projectInfo.deployToNamespace} | egrep "(\${FOR_DELETE_DCS})-[0-9]+-deploy" | xargs)
        # just in case first one doesn't take (sometimes happens if there was no image change)
        if [[ ! -z \${FOR_DELETE_PODS} ]]
        then
            oc delete pods --ignore-not-found \${FOR_DELETE_PODS} -n ${projectInfo.deployToNamespace} 
        fi
    """

    waitingForPodsToTerminate(projectInfo.deployToNamespace)

    sh """
        ${pipelineUtils.shellEchoBanner("ROLLOUT LATEST IN ${projectInfo.deployToNamespace} FROM ARTIFACT REPOSITORY:", "${microServiceNames}")}

        DCS="\$(oc get dc --ignore-not-found -l build-number=\${BUILD_NUMBER} -o 'custom-columns=:.metadata.name' -n ${projectInfo.deployToNamespace} | xargs)"
        # just in case first one doesn't take (sometimes happens if there was no image change)
        for I in {1..2}
        do
            for DC in \${DCS}
            do
                ${shellEcho ''}
                oc rollout latest dc/\${DC} -n ${projectInfo.deployToNamespace} 2> /dev/null || echo "Confirmed \${DC} rolling out..."
            done
            set +x
            sleep 3
        done
        set -x
    """
}

def confirmDeployments(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    def microServiceNames = microServices.collect { microService -> microService.name }.join(' ')
    sh """
        ${pipelineUtils.shellEchoBanner("CONFIRM DEPLOYMENT IN ${projectInfo.deployToNamespace} FROM ARTIFACT REPOSITORY:", "${microServiceNames}")}

        JSONPATH='jsonpath={range .items[*]}{ .metadata.name }{" "}{end}'
        DCS="\$(oc get dc --ignore-not-found -l build-number=\${BUILD_NUMBER} -o "\${JSONPATH}" -n ${projectInfo.deployToNamespace} | tr '\n' ' ' | xargs)"
        for DC in \${DCS}
        do
            ${shellEcho ''}
            oc rollout status dc/\${DC} -n ${projectInfo.deployToNamespace}
        done
    """
}

def updateMicroServiceMetaInfo(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    microServices.each { microService ->
        def metaInfoCmName = "${projectInfo.id}-${microService.name}-${el.cicd.CM_META_INFO_POSTFIX}"

        sh """
            ${pipelineUtils.shellEchoBanner("UPDATE LABELS AND ${metaInfoCmName}:",
                                            "projectid = ${projectInfo.id}", 
                                            "microservice = ${microService.name}",
                                            "git-repo = ${microService.gitRepoName}",
                                            "src-commit-hash = ${microService.srcCommitHash}",
                                            "deployment-branch = ${microService.deploymentBranch ?: UNDEFINED}",
                                            "deployment-commit-hash = ${microService.deploymentCommitHash}",
                                            "release-version = ${projectInfo.releaseVersionTag ?: UNDEFINED}",
                                            "build-number = ${BUILD_NUMBER"})}

            oc delete --ignore-not-found cm ${metaInfoCmName} -n ${projectInfo.deployToNamespace}

            ${shellEcho ''}
            oc create cm ${metaInfoCmName} \
                --from-literal=projectid=${projectInfo.id} \
                --from-literal=microservice=${microService.name} \
                --from-literal=git-repo=${microService.gitRepoName} \
                --from-literal=src-commit-hash=${microService.srcCommitHash} \
                --from-literal=deployment-branch=${microService.deploymentBranch ?: UNDEFINED} \
                --from-literal=deployment-commit-hash=${microService.deploymentCommitHash} \
                --from-literal=release-version=${projectInfo.releaseVersionTag ?: UNDEFINED} \
                --from-literal=build-number=${BUILD_NUMBER} \
                -n ${projectInfo.deployToNamespace}

            ${shellEcho ''}
            oc label cm ${metaInfoCmName} \
                projectid=${projectInfo.id} \
                microservice=${microService.name} \
                git-repo=${microService.gitRepoName} \
                src-commit-hash=${microService.srcCommitHash} \
                deployment-branch=${microService.deploymentBranch ?: UNDEFINED} \
                deployment-commit-hash=${microService.deploymentCommitHash} \
                release-version=${projectInfo.releaseVersionTag ?: UNDEFINED} \
                build-number=${BUILD_NUMBER} \
                -n ${projectInfo.deployToNamespace}
        """
    }
}

def cleanupOrphanedResources(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    microServices.each { microService ->
        sh """
            ${pipelineUtils.shellEchoBanner("REMOVING ALL RESOURCES FOR ${microService.name} THAT ARE NOT PART OF DEPLOYMENT COMMIT ${microService.deploymentCommitHash}")}

            oc delete dc,svc,rc,hpa,configmaps,sealedsecrets,routes,cronjobs \
                -l microservice=${microService.name},deployment-commit-hash!=${microService.deploymentCommitHash} \
                -n ${projectInfo.deployToNamespace}
        """
    }
}

def removeAllMicroservices(def projectInfo) {
    assert projectInfo

    sh """
        ${pipelineUtils.shellEchoBanner("REMOVING ALL MICROSERVICES AND RESOURCES FROM ${projectInfo.deployToNamespace} FOR PROJECT ${projectInfo.id}")}

        oc delete dc,svc,rc,hpa,configmaps,sealedsecrets,routes,cronjobs -l microservice -n ${projectInfo.deployToNamespace}
    """

    waitingForPodsToTerminate(projectInfo.deployToNamespace)
}

def removeMicroservices(def projectInfo, def microServices) {
    assert projectInfo; assert microServices

    def microServiceNames = microServices.collect { microService -> microService.name }.join(' ')

    sh """
        ${pipelineUtils.shellEchoBanner("REMOVE SELECTED MICROSERVICES AND ALL ASSOCIATED RESOURCES FROM ${projectInfo.deployToNamespace}:", "${microServiceNames}")}

        for MICROSERVICE_NAME in ${microServiceNames}
        do
            oc delete dc,svc,rc,hpa,configmaps,sealedsecrets,routes,cronjobs -l microservice=\${MICROSERVICE_NAME} -n ${projectInfo.deployToNamespace}
        done
    """

    waitingForPodsToTerminate(projectInfo.deployToNamespace)
}

def waitingForPodsToTerminate(def deployToNamespace) {
    sh """
        ${shellEcho '', 'Waiting for microservice pods to terminate...'}
        set +x
        sleep 3
        COUNTER=1
        while [[ ! -z \$(oc get pods -n ${deployToNamespace} | grep 'Terminating') ]]
        do
            printf "%0.s-" \$(seq 1 \${COUNTER})
            echo
            sleep 3
            let COUNTER+=1
        done
        set -x
    """
}
