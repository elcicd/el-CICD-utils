/*
 * Called at the beginning of every pipeline to initilize map of el-CICD
 * meta-info.
 */

import groovy.transform.Field

@Field
static def cicd = [:]

def init(def metaData) {
    cicd.putAll(metaData)

    cicd.TEST_ENVS = cicd.TEST_ENVS.split(':')

    cicd.testEnvs = cicd.TEST_ENVS.collect { it.toLowerCase() }
    cicd.devEnv = cicd.DEV_ENV.toLowerCase()
    cicd.prodEnv = cicd.PROD_ENV.toLowerCase()

    cicd.IGNORE = 'IGNORE'
    cicd.PROMOTE = 'PROMOTE'
    cicd.REMOVE = 'REMOVE'

    cicd.INACTIVE = 'INACTIVE'

    cicd.CLEAN_K8S_RESOURCE_COMMAND = "egrep -v -h 'namespace:|creationTimestamp:|uid:|selfLink:|resourceVersion:|generation:'"

    cicd.DEPLOYMENT_BRANCH_PREFIX = 'deployment'
}