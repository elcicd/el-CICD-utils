#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_refresh_el_cicd_credentials() {
    set -e
    
    __create_el_cicd_git_readonly_deploy_keys

    GIT_ACCESS_TOKEN=$(cat ${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE})
    _delete_git_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                                ${EL_CICD_ORGANIZATION} \
                                ${EL_CICD_REPO} \
                                ${GIT_ACCESS_TOKEN} \
                                ${EL_CICD_MASTER_NAMESPACE}
    
    _add_git_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                             ${EL_CICD_ORGANIZATION} \
                             ${EL_CICD_REPO} \
                             ${GIT_ACCESS_TOKEN} \
                             ${EL_CICD_MASTER_NAMESPACE} \
                             ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}

    _delete_git_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                                ${EL_CICD_ORGANIZATION} \
                                ${EL_CICD_CONFIG_REPO} \
                                ${GIT_ACCESS_TOKEN} \
                                ${EL_CICD_MASTER_NAMESPACE}

    _add_git_repo_deploy_key ${EL_CICD_GIT_API_URL} \
                             ${EL_CICD_ORGANIZATION} \
                             ${EL_CICD_CONFIG_REPO} \
                             ${GIT_ACCESS_TOKEN} \
                             ${EL_CICD_MASTER_NAMESPACE} \
                             ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}
    
    __create_jenkins_secrets
   
    if [[ ${EL_CICD_MASTER_NONPROD} == ${_TRUE} ]]
    then        
        _run_custom_config_script credentials-non-prod.sh
    fi
    
    if [[ ${EL_CICD_MASTER_PROD} == ${_TRUE} ]]
    then
        _run_custom_config_script credentials-prod.sh
    fi

    echo 
    echo '--> el-CICD Master credentials refresh complete'
    set +e
}

__create_el_cicd_git_readonly_deploy_keys() {
    mkdir -p ${SECRET_FILE_DIR}

    echo
    echo 'Creating el-CICD read-only Git repository ssh key files:'
    echo ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}
    local _FILE="${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}"
    ssh-keygen -b 2048 -t rsa -f "${_FILE}" -q -N '' 2>/dev/null <<< y >/dev/null

    echo
    echo 'Creating el-CICD-config read-only Git repository ssh key files:'
    echo ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}
    local _FILE="${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}"
    ssh-keygen -b 2048 -t rsa -f "${_FILE}" -q -N '' 2>/dev/null <<< y >/dev/null
}

__create_jenkins_secrets() {
    local _OCI_REGISTRY_IDS=$(_get_oci_registry_ids)
    _OCI_REGISTRY_IDS+="  ${HELM} "

    local _SET_FLAGS=$(__create_image_registry_values_flags "${_OCI_REGISTRY_IDS}")
    
	if [[ "${EL_CICD_MASTER_NONPROD}" && "$(ls -A ${BUILD_SECRETS_FILE_DIR})" ]]
    then
        local _BUILDER_SECRETS_PROFILE='BUILDER_SECRETS'
        _SET_FLAGS+="${_SET_FLAGS:+ }$(__create_builder_secret_flags)"
    fi
    
    if [[ ${EL_CICD_JENKINS_SECRETS_CHART} ]]
    then
        helm uninstall --wait ${EL_CICD_JENKINS_SECRETS_CHART} -n ${EL_CICD_MASTER_NAMESPACE}
    fi

    echo
    echo "Creating all Jenkins secrets..."
    echo
    _OCI_REGISTRY_IDS=$(echo ${_OCI_REGISTRY_IDS@L} | sed -e 's/\s\+/,/g')
    local _GIT_REPO_KEYS="${EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID},${EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID}"
    set -e
    helm upgrade --install --atomic --create-namespace --history-max=1 \
        --set elCicdProfiles="{${_BUILDER_SECRETS_PROFILE}}"  \
        --set-string elCicdDefs.BUILD_SECRETS_NAME=${EL_CICD_BUILD_SECRETS_NAME} \
        --set-string elCicdDefs.OCI_REGISTRY_IDS="{${_OCI_REGISTRY_IDS}}" \
        --set-string elCicdDefs.GIT_REPO_SSH_KEY_IDS="{${_GIT_REPO_KEYS}}" \
        --set-file elCicdDefs-${EL_CICD_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID}.GIT_REPO_SSH_KEY=${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE} \
        --set-file elCicdDefs-${EL_CICD_CONFIG_GIT_REPO_READ_ONLY_GITHUB_PRIVATE_KEY_ID}.GIT_REPO_SSH_KEY=${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE} \
        --set-string elCicdDefs.GIT_ACCESS_TOKEN_ID=${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_ID} \
        --set-file elCicdDefs.GIT_ACCESS_TOKEN=${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE} \
        ${_SET_FLAGS} \
        -f ${EL_CICD_DIR}/${BOOTSTRAP_CHART_DEPLOY_DIR}/elcicd-jenkins-secrets-values.yaml \
        -n ${EL_CICD_MASTER_NAMESPACE} \
        elcicd-jenkins-secrets \
        ${EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
    set +e
}

__create_image_registry_values_flags() {
    local _OCI_REGISTRY_IDS=${1}
    
    for OCI_REGISTRY_ID in ${_OCI_REGISTRY_IDS@L}
    do
        local _OCI_USERNAME=$(_get_oci_username ${OCI_REGISTRY_ID})
        local _OCI_PASSWORD=$(_get_oci_password ${OCI_REGISTRY_ID})
        
        local _SET_FLAGS+="${_SET_FLAGS:+ }--set-string elCicdDefs-${OCI_REGISTRY_ID}.REGISTRY_USERNAME=${_OCI_USERNAME}"
        _SET_FLAGS+="${_SET_FLAGS:+ }--set-string elCicdDefs-${OCI_REGISTRY_ID}.REGISTRY_PASSWORD=${_OCI_PASSWORD}"
        
        local _REGISTRY_URL=${OCI_REGISTRY_ID@U}${OCI_REGISTRY_POSTFIX}
        _SET_FLAGS+="${_SET_FLAGS:+ }--set-string elCicdDefs-${OCI_REGISTRY_ID}.REGISTRY_URL=${!_REGISTRY_URL}"
    done

    echo ${_SET_FLAGS}
}

__create_builder_secret_flags() {
    for BUILDER_SECRET_FILE in ${BUILD_SECRETS_FILE_DIR}/*
    do
        local _BUILDER_SECRET_KEY=$(basename ${BUILDER_SECRET_FILE})
        local _SET_FLAGS="${_SET_FLAGS:+${_SET_FLAGS} }--set-file elCicdDefs.BUILDER_SECRET_FILES.${_BUILDER_SECRET_KEY//[.]/\\.}=${BUILDER_SECRET_FILE}"
    done

    echo ${_SET_FLAGS}
}

