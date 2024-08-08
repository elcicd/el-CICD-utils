#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_bootstrap_el_cicd() {
    if [[ -z "${EL_CICD_MASTER_NAMESPACE}" ]]
    then
        echo "The el-CICD master project must be defined in ${ROOT_CONFIG_FILE}"
        echo "Set the value of EL_CICD_MASTER_NAMESPACE ${ROOT_CONFIG_FILE} and rerun."
        echo "Exiting."
        exit 1
    fi
    
    _cluster_info

    __gather_bootstrap_info

    __summarize_and_confirm_bootstrap_run_with_user

    if [[ ${INSTALL_SEALED_SECRETS} == ${_YES} ]]
    then
        _install_sealed_secrets
    fi

    if [[ -z ${JENKINS_MASTER_IMAGE_SHA} ]]
    then
        _build_el_cicd_jenkins_image
    fi

    if [[ ${BUILD_JENKINS_AGENTS} == ${_TRUE} ]]
    then
        _build_el_cicd_jenkins_agent_images
    fi

    __bootstrap_el_cicd_onboarding_server

    if [[ ${EL_CICD_MASTER_NONPROD} == ${_TRUE} ]]
    then
        _run_custom_config_script bootstrap-non-prod.sh
    fi

    if [[ ${EL_CICD_MASTER_PROD} == ${_TRUE} ]]
    then
        _run_custom_config_script bootstrap-prod.sh
    fi

    echo
    echo 'Custom Onboarding Server Boostrap Script(s) Complete'

    echo
    echo "el-CICD Onboarding Server Bootstrap Script Complete:"
    echo "https://${JENKINS_MASTER_URL}"
}

__gather_bootstrap_info() {
    _collect_sealed_secret_info

    EL_CICD_MASTER_NAMESPACE_EXISTS=$(oc get project ${EL_CICD_MASTER_NAMESPACE} -o name --no-headers --ignore-not-found)
    if [[ -z ${EL_CICD_MASTER_NAMESPACE_EXISTS} && -z ${HAS_SEALED_SECRETS} ]]
    then
        _confirm_upgrade_install_sealed_secrets
    fi
}

__summarize_and_confirm_bootstrap_run_with_user() {
    echo
    echo "===================== ${_BOLD}SUMMARY${_REGULAR} ====================="

    echo
    echo 'el-CICD Bootstrap will perform the following actions based on the summary below.'
    echo "${_BOLD}Please read CAREFULLY and verify this information is correct before proceeding.${_REGULAR}"

    echo
    echo "${_BOLD}${ELCICD_ADM_MSG}${_REGULAR}"

    if [[ ${INSTALL_SEALED_SECRETS} == ${_YES} ]]
    then
        echo
        echo "Sealed Secrets ${SEALED_SECRETS_RELEASE_INFO} ${_BOLD}WILL${_REGULAR} be installed."
    fi

    get_jenkins_image_sha
    if [[ -z ${JENKINS_MASTER_IMAGE_SHA} ]]
    then
        echo "${_BOLD}WARNING:${_REGULAR} ${JENKINS_OCI_REGISTRY}/${JENKINS_IMAGE_NAME} image was not found."
        echo
        echo "The el-CICD Jenkins image ${_BOLD}WILL BE BUILT${_REGULAR}."
    fi

    local _JENKINS_BASE_AGENT_EXISTS=$(_base_jenkins_agent_exists)
    if [[ ${_JENKINS_BASE_AGENT_EXISTS} == ${_FALSE} ]]
    then
        local _JENKINS_BASE_AGENT=${JENKINS_OCI_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${JENKINS_AGENT_DEFAULT}
        echo
        echo "${_BOLD}WARNING:${_REGULAR} THE JENKINS BASE AGENT ${_JENKINS_BASE_AGENT} WAS NOT FOUND."
        if [[ ${JENKINS_SKIP_AGENT_BUILDS} == ${_TRUE} ]]
        then
            echo
            echo "${_BOLD}JENKINS_SKIP_AGENT_BUILDS IS TRUE:${_REGULAR} Jenkins agents will not be built."
            echo "To manually rebuild Jenkins Agents, run the 'elcicd-adm' utility with the --agents flag."
        else
            echo
            echo "All Jenkins agents ${_BOLD}WILL BE BUILT${_REGULAR}."
            BUILD_JENKINS_AGENTS=${_TRUE}
        fi
    fi

    echo
    local _EL_CICD_MASTER_NAMESPACE_RESULT
    case "${EL_CICD_MASTER_NAMESPACE_EXISTS}" in
        '') _EL_CICD_MASTER_NAMESPACE_RESULT='create and install' ;;
         *) _EL_CICD_MASTER_NAMESPACE_RESULT='refresh and upgrade' ;;
    esac

    echo "${EL_CICD_MASTER_NAMESPACE} namespace and el-CICD Master: ${_BOLD}${_EL_CICD_MASTER_NAMESPACE_RESULT}${_REGULAR}"

    echo
    echo "=================== ${_BOLD}END SUMMARY${_REGULAR} ==================="

    if [[ "${EL_CICD_ASSUME_YES}" != "${_TRUE}" ]]
    then
        _confirm_continue
    fi
}

_cluster_info() {
    echo
    oc status
    if [[ $? != 0 ]]
    then
        echo
        echo 'ERROR: Unable to reach cluster, or not logged in; EXITING...'
        exit 1
    fi
    echo "Cluster wildcard Domain: ${_BOLD}*.${CLUSTER_WILDCARD_DOMAIN}${_REGULAR}"

    echo
    echo "You ${_BOLD}MUST${_REGULAR} be currently logged into a cluster as a cluster admin."
    if [[ "${OKD_VERSION}" ]]
    then
        echo "Logged in as: ${_BOLD}$(oc whoami)${_REGULAR}"
    fi
}

_create_and_source_meta_info_files() {
    echo
    echo 'Loaded the following el-CICD scripts...'
    echo
    for FILE in $(echo ${EL_CICD_SCRIPTS} | xargs -n 1 basename)
    do
        echo "- ${FILE}"
    done
    
    set -e -o allexport

    echo
    echo "GENERATING CONFIG FILES:"

    source ${ROOT_CONFIG_FILE}

    local _EL_CICD_CONSTANTS_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/constants.conf

    local _EL_CICD_LAB_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/lab.conf

    local _EL_CICD_RUNTIME_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/runtime.conf

    local _EL_CICD_JENKINS_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/jenkins.conf

    EL_CICD_META_INFO_FILE=/tmp/el_cicd_meta_info_file.conf
    local _CONFIG_FILE_LIST="${_EL_CICD_CONSTANTS_CONF} ${ROOT_CONFIG_FILE}  ${_EL_CICD_LAB_CONF} ${_EL_CICD_RUNTIME_CONF} ${_EL_CICD_JENKINS_CONF}"
    __create_meta_info_file "${_CONFIG_FILE_LIST}" ${EL_CICD_META_INFO_FILE}

    local _EL_CICD_BOOTSTRAP_CONF=${EL_CICD_SCRIPTS_CONFIG_DIR}/bootstrap.conf

    EL_CICD_BOOTSTRAP_META_INFO_FILE=/tmp/el_cicd_bootstrap_meta_info_file.conf
    local _CONFIG_FILE_LIST="${EL_CICD_META_INFO_FILE} ${_EL_CICD_BOOTSTRAP_CONF}"
    __create_meta_info_file "${_CONFIG_FILE_LIST}" ${EL_CICD_BOOTSTRAP_META_INFO_FILE}

    source ${EL_CICD_BOOTSTRAP_META_INFO_FILE}

    echo
    echo 'el-CICD environment loaded'

    set +e +o allexport
}

__create_meta_info_file() {
    local _CONF_FILE_LIST=${1}
    local _META_INFO_FILE=${2}

    local _META_INFO_FILE_TMP=${_META_INFO_FILE}.tmp

    rm -f ${_META_INFO_FILE} ${_META_INFO_FILE_TMP}

    # ignore duplicate values: config file precedence is left to right
    awk -F= '!line[$1]++' ${_CONF_FILE_LIST} >> ${_META_INFO_FILE_TMP}

    # remove blank lines, comments, and any trailing whitespace
    sed -i -e 's/\s*$//' -e '/^$/d' -e '/^#.*$/d' ${_META_INFO_FILE_TMP}

    echo "EL_CICD_MASTER_NONPROD=${EL_CICD_MASTER_NONPROD}" >> ${_META_INFO_FILE_TMP}
    echo "EL_CICD_MASTER_PROD=${EL_CICD_MASTER_PROD}" >> ${_META_INFO_FILE_TMP}
    
    if [[ $(oc get --raw /readyz >/dev/null) ]]
    then
        echo "EL_CICD_CLUSTER_UID=$(oc get namespace kube-system -o jsonpath='{.metadata.uid}')" >> ${_META_INFO_FILE_TMP}
    fi

    sort -o ${_META_INFO_FILE_TMP} ${_META_INFO_FILE_TMP}

    source ${_META_INFO_FILE_TMP}
    cat ${_META_INFO_FILE_TMP} | envsubst > ${_META_INFO_FILE}

    rm -f ${_META_INFO_FILE_TMP}

    echo
    echo "${_BOLD}${_META_INFO_FILE}${_REGULAR} created from the following config files:"
    for CONF_FILE in ${_CONF_FILE_LIST}
    do
        echo "- $(basename ${CONF_FILE}) "
    done
    sleep 2
}

_create_rbac_helpers() {
    local _HAS_SEALED_SECRETS=$(helm list --short --filter 'sealed-secrets' -n kube-system)
    if [[ ${INSTALL_SEALED_SECRETS} != ${_YES} || "${_HAS_SEALED_SECRETS}" ]]
    then
        local _SET_PROFILES='SEALED_SECRETS'
    fi
    
    if [[ ${OKD_VERSION} ]]
    then
        local _OKD_RBAC_VALUES_FILE="${EL_CICD_DIR}/${BOOTSTRAP_CHART_DEPLOY_DIR}/elcicd-okd-scc-nonroot-builder-values.yaml"
        _SET_PROFILES+="${_SET_PROFILES:+,}OKD"
    fi

    echo
    echo 'Installing el-CICD RBAC helpers.'
    echo
    set -ex
    helm upgrade --install --atomic --history-max=1 \
        --set-string elCicdProfiles={${_SET_PROFILES}} \
        -f ${_OKD_RBAC_VALUES_FILE} \
        -f ${EL_CICD_DIR}/${BOOTSTRAP_CHART_DEPLOY_DIR}/elcicd-cluster-rbac-values.yaml \
        -n kube-system \
        elcicd-cluster-rbac-resources \
        ${EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
    set +ex
}

__bootstrap_el_cicd_onboarding_server() {
    echo
    echo "======= BE AWARE: ONBOARDING REQUIRES CLUSTER ADMIN PERMISSIONS ======="
    echo
    echo "Be aware that the el-CICD Onboarding 'jenkins' service account needs cluster-admin"
    echo "permissions for managing and creating multiple cluster resources RBAC"
    echo
    echo "NOTE: This DOES NOT apply to CICD servers"
    echo
    echo "======= BE AWARE: ONBOARDING REQUIRES CLUSTER ADMIN PERMISSIONS ======="
    sleep 5

    _create_rbac_helpers

    if [[ -z ${EL_CICD_JENKINS_SECRETS_CHART} || ${EL_CICD_SKIP_CREDENTIAL_REFRESH} != ${_TRUE} ]]
    then
        _refresh_el_cicd_credentials
    else
        echo
        echo 'CREDENTIAL REFRESH SKIPPED'
    fi

    __create_onboarding_automation_server
}

__create_onboarding_automation_server() {
    local _PROFILES='ONBOARDING'
    _PROFILES="${_PROFILES}${OKD_VERSION:+,OKD}"
    _PROFILES="${_PROFILES}${JENKINS_MASTER_PERSISTENT:+,JENKINS_PERSISTENT}"
    _PROFILES="${_PROFILES}${EL_CICD_MASTER_NONPROD:+,NONPROD}"
    _PROFILES="${_PROFILES}${EL_CICD_MASTER_PROD:+,PROD}"

    local _JENKINS_DEPLOYMENT_NAME='jenkins'

    __remove_failed_jenkins_server ${_JENKINS_DEPLOYMENT_NAME}

    if [[ -z ${JENKINS_MASTER_IMAGE_SHA} ]]
    then
        get_jenkins_image_sha
    fi

    echo
    echo 'Upgrading/Installing el-CICD Master Server'
    echo
    JENKINS_OPENSHIFT_ENABLE_OAUTH=${OKD_VERSION:+'true'}${OKD_VERSION:-'false'}
    set -ex
    helm upgrade --install --atomic --create-namespace --history-max=1 \
        --set-string elCicdProfiles="{${_PROFILES}}" \
        --set-string elCicdDefs.JENKINS_IMAGE=${JENKINS_OCI_REGISTRY}/${JENKINS_IMAGE_NAME}@${JENKINS_MASTER_IMAGE_SHA} \
        --set-string elCicdDefs.JENKINS_URL=${JENKINS_MASTER_URL} \
        --set-string elCicdDefs.OPENSHIFT_ENABLE_OAUTH=${JENKINS_OPENSHIFT_ENABLE_OAUTH} \
        --set-string elCicdDefs.JENKINS_CPU_REQUEST=${JENKINS_MASTER_CPU_REQUEST} \
        --set-string elCicdDefs.JENKINS_MEMORY_REQUEST=${JENKINS_MASTER_MEMORY_REQUEST} \
        --set-string elCicdDefs.JENKINS_MEMORY_LIMIT=${JENKINS_MASTER_MEMORY_LIMIT} \
        --set-string elCicdDefs.JENKINS_AGENT_CPU_REQUEST=${JENKINS_AGENT_CPU_REQUEST} \
        --set-string elCicdDefs.JENKINS_AGENT_MEMORY_REQUEST=${JENKINS_AGENT_MEMORY_REQUEST} \
        --set-string elCicdDefs.JENKINS_AGENT_MEMORY_LIMIT=${JENKINS_AGENT_MEMORY_LIMIT} \
        --set-string elCicdDefs.VOLUME_CAPACITY=${JENKINS_MASTER_VOLUME_CAPACITY} \
        --set-string elCicdDefs.EL_CICD_META_INFO_NAME=${EL_CICD_META_INFO_NAME} \
        --set-string elCicdDefs.JENKINS_UC=${JENKINS_UC} \
        --set-string elCicdDefs.JENKINS_UC_INSECURE=${JENKINS_UC_INSECURE} \
        --set-string elCicdDefs.JENKINS_CONFIG_FILE_PATH=${JENKINS_CONFIG_FILE_PATH} \
        --set-file 'elCicdDefs.$<CONFIG|EL_CICD_META_INFO>'=${EL_CICD_META_INFO_FILE} \
        --set-file elCicdDefs.JENKINS_CASC_FILE=${EL_CICD_CONFIG_JENKINS_DIR}/${JENKINS_MASTER_CASC_FILE} \
        --set-file elCicdDefs.JENKINS_PLUGINS_FILE=${EL_CICD_CONFIG_JENKINS_DIR}/${JENKINS_MASTER_PLUGINS_FILE} \
        -f ${EL_CICD_CONFIG_DIR}/${CHART_DEPLOY_DIR}/default-elcicd-master-values.yaml \
        -f ${EL_CICD_DIR}/${BOOTSTRAP_CHART_DEPLOY_DIR}/elcicd-master-pipelines-values.yaml \
        -f ${EL_CICD_DIR}/${JENKINS_CHART_DEPLOY_DIR}/elcicd-jenkins-pipeline-template-values.yaml \
        -f ${EL_CICD_DIR}/${JENKINS_CHART_DEPLOY_DIR}/jenkins-config-values.yaml \
        -n ${EL_CICD_MASTER_NAMESPACE} \
        ${_JENKINS_DEPLOYMENT_NAME} \
        ${EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
    set +ex
    sleep 3

    echo
    echo 'JENKINS UP'

    local _JSONPATH="jsonpath='{.items[?(@.metadata.deletionTimestamp)].metadata.name}'"
    local _TERMINATING_POD=$(oc get pods -n ${EL_CICD_MASTER_NAMESPACE} -l name=jenkins -o=${_JSONPATH} | tr '\n' ' ')
    if [[ "${TERMINATING_PODS}" ]]
    then
        echo
        echo 'Wait for old Jenkins pod to terminate...'

        oc wait --for=delete pod ${_TERMINATING_POD} -n ${EL_CICD_MASTER_NAMESPACE} --timeout=600s
    fi

    if [[ ! -z $(helm list -n ${EL_CICD_MASTER_NAMESPACE} | grep sync-jenkins-pipelines) ]]
    then
        echo
        echo 'Removing old Jenkins pipeline sync job.'
        helm uninstall sync-jenkins-pipelines -n ${EL_CICD_MASTER_NAMESPACE}
    fi

    echo
    echo 'Running Jenkins pipeline sync job for el-CICD Master.'
    echo
    set -ex
    helm install --atomic --wait-for-jobs \
        --set-string elCicdDefs.JENKINS_SYNC_JOB_IMAGE=${JENKINS_OCI_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${JENKINS_AGENT_DEFAULT} \
        --set-string elCicdDefs.JENKINS_CONFIG_FILE_PATH=${JENKINS_CONFIG_FILE_PATH}/ \
        -n ${EL_CICD_MASTER_NAMESPACE} \
        -f ${EL_CICD_DIR}/${JENKINS_CHART_DEPLOY_DIR}/sync-jenkins-pipelines-job-values.yaml \
        sync-jenkins-pipelines \
        ${EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
    set +ex
}

__remove_failed_jenkins_server() {
    local _JENKINS_DEPLOYMENT_NAME=${1}

    if [[ $(helm list --failed -q -n ${EL_CICD_MASTER_NAMESPACE} | grep -E ^${_JENKINS_DEPLOYMENT_NAME}$) ]]
    then
        echo
        echo 'Removing failed/incomplete Jenkins deployment'
        helm uninstall ${_JENKINS_DEPLOYMENT_NAME} -n ${EL_CICD_MASTER_NAMESPACE}
    fi
}

_run_custom_config_script() {
    local CUSTOM_CONFIG_SCRIPT=${1}

    echo
    echo "LOOKING FOR CUSTOM CONFIGURATION SCRIPT '${CUSTOM_CONFIG_SCRIPT}' in ${EL_CICD_CONFIG_BOOTSTRAP_DIR}..."
    if [[ -f ${EL_CICD_CONFIG_BOOTSTRAP_DIR}/${CUSTOM_CONFIG_SCRIPT} ]]
    then
        echo "${_BOLD}FOUND ${CUSTOM_CONFIG_SCRIPT}${_REGULAR}; running..."
        ${EL_CICD_CONFIG_BOOTSTRAP_DIR}/${CUSTOM_CONFIG_SCRIPT}
        echo "Custom script ${CUSTOM_CONFIG_SCRIPT} completed"
    else
        echo "Custom script '${CUSTOM_CONFIG_SCRIPT}' ${_BOLD}NOT FOUND${_REGULAR}."
    fi
}