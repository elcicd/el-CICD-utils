#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_tear_down_lab_environment() {
    set -eE
    
    echo
    echo "${DEV_TEAR_DOWN_WELCOME_MSG}"

    __gather_lab_tear_down_info

    __summarize_and_confirm_lab_tear_down

    if [[ ${REMOVE_CRC} == ${_YES} ]]
    then
        _remove_existing_crc
    fi

    if [[ ${REMOVE_OCI_REGISTRY} == ${_YES} ]]
    then
        _remove_image_registry

        __remove_whitelisted_image_registry_host_names
    fi

    if [[ ${REMOVE_OCI_REGISTRY_NFS} == ${_YES} ]]
    then
        __remove_image_registry_nfs_share
    fi

    if [[ ${REMOVE_GIT_REPOS} == ${_YES} && ${EL_CICD_ORGANIZATION} != ${DEFAULT_EL_CICD_ORGANIZATION_NAME} ]]
    then
        __delete_remote_el_cicd_git_repos
    fi
}

__gather_lab_tear_down_info() {
    echo
    if [[ -d ${HOME}/.crc ]]
    then
        REMOVE_CRC=$(_get_yes_no_answer 'Do you wish to tear down OpenShift Local? [Y/n] ')
    else
        echo 'OpenShift Local installation not found.  Skipping...'
    fi

    if [[ ${REMOVE_CRC} != ${_YES} ]]
    then
        _confirm_logged_into_cluster
        
        if [[ ! -z $(oc get namespace --ignore-not-found --no-headers ${DEMO_OCI_REGISTRY}) ]]
        then
            echo
            REMOVE_OCI_REGISTRY=$(_get_yes_no_answer 'Do you wish to remove the development image registry? [Y/n] ')
        else
            REGISTRY_NOT_FOUND=${_TRUE}
        fi
    fi

    if [[ -d ${DEMO_OCI_REGISTRY_DATA_NFS_DIR} && \
            (${REMOVE_OCI_REGISTRY} == ${_YES} || ${REMOVE_CRC} == ${_YES} || ${REGISTRY_NOT_FOUND} == ${_TRUE})  ]]
    then
        REMOVE_OCI_REGISTRY_NFS=$(_get_yes_no_answer 'Do you wish to remove the image registry NFS share? [Y/n] ')
    fi

    if [[ ${EL_CICD_ORGANIZATION} != ${DEFAULT_EL_CICD_ORGANIZATION_NAME} ]]
    then
        echo
        REMOVE_GIT_REPOS=$(_get_yes_no_answer 'Do you wish to remove the el-CICD Git repositories? [Y/n] ')
        if [[ ${REMOVE_GIT_REPOS} == ${_YES} ]]
        then
            echo
            read -p "Enter GitHub user name (leave blank to leave as ${EL_CICD_ORGANIZATION}): " EL_CICD_ORGANIZATION_TEMP
            EL_CICD_ORGANIZATION=${EL_CICD_ORGANIZATION_TEMP:-${EL_CICD_ORGANIZATION}}

            read -s -p "Enter GitHub Personal Access Token (leave blank to use credential file): " EL_CICD_GIT_REPO_ACCESS_TOKEN
        fi
    fi
}

__summarize_and_confirm_lab_tear_down() {
    echo
    echo "${_BOLD}===================== ${_BOLD}SUMMARY${_REGULAR} =====================${_REGULAR}"
    echo 

    if [[ ${REMOVE_CRC} == ${_YES} ]]
    then
        echo "OpenShift Local ${_BOLD}WILL${_REGULAR} be torn down.  The image registry ${_BOLD}WILL${_REGULAR} also be torn down as a result."
    else 
        echo "OpenShift Local will ${_BOLD}NOT${_REGULAR} be torn down."

        if [[ ${REMOVE_OCI_REGISTRY} == ${_YES} ]]
        then
            echo "The image registry ${_BOLD}WILL${_REGULAR} be torn down."
        elif [[ ${REGISTRY_NOT_FOUND} == ${_TRUE} ]]
        then
            echo "The image registry was ${_BOLD}NOT${_REGULAR} found."
        else
            echo "The image registry will ${_BOLD}NOT${_REGULAR} be torn down."
        fi
    fi

    echo -n "The image registry NFS share "
    if [[ ${REMOVE_OCI_REGISTRY_NFS} == ${_YES} ]]
    then
        echo -n "${_BOLD}WILL${_REGULAR}"
    else
        echo -n "will ${_BOLD}NOT${_REGULAR}"
    fi
    echo " be deleted and removed."

    echo -n "All el-CICD Git repositories "
    if [[ ${REMOVE_GIT_REPOS} == ${_YES} ]]
    then
        echo -n "${_BOLD}WILL${_REGULAR}"
    else
        echo -n "will ${_BOLD}NOT${_REGULAR}"
    fi
    echo " be removed from ${EL_CICD_ORGANIZATION} on the Git host."
    
    echo
    echo "${_BOLD}=================== ${_BOLD}END SUMMARY${_REGULAR} ===================${_REGULAR}"

    _confirm_continue
}

_remove_existing_crc() {
    CRC_EXEC=$(find ${EL_CICD_HOME} -name crc)

    set +e
    if [[ "${CRC_EXEC}" ]]
    then
        echo
        echo 'Stopping the OpenShift Local cluster'
        ${CRC_EXEC} stop
        ${CRC_EXEC} delete
        echo 'Cleaning up the OpenShift Local install'
        ${CRC_EXEC} cleanup
        unset CRC_EXEC
    fi
    set -e

    echo
    echo 'Removing old OpenShift Local installation directories'
    rm -rfv ${EL_CICD_HOME}/crc*/
    rm -rfv ${HOME}/.crc
}

_remove_image_registry() {
    if [[ ! -z $(helm list -q -n ${DEMO_OCI_REGISTRY} -f ${DEMO_OCI_REGISTRY}) ]]
    then
        echo
        echo "Uninstalling ${DEMO_OCI_REGISTRY}..."
        helm uninstall ${DEMO_OCI_REGISTRY} -n ${DEMO_OCI_REGISTRY}

        local _REGISTRY_NAMES=$(echo ${DEMO_OCI_REGISTRY_NAMES} | tr ':' ' ')
        local _IMAGE_CONFIG_CLUSTER=$(oc get image.config.openshift.io/cluster -o yaml)
        for REGISTRY_NAME in ${_REGISTRY_NAMES}
        do
            local _HOST_DOMAIN=${REGISTRY_NAME}-${DEMO_OCI_REGISTRY}.${CLUSTER_WILDCARD_DOMAIN}

            _IMAGE_CONFIG_CLUSTER=$(echo "${_IMAGE_CONFIG_CLUSTER}" | grep -v "\- ${_HOST_DOMAIN}")
        done
        echo "${_IMAGE_CONFIG_CLUSTER}" | oc apply -f -
    fi
    oc delete project --ignore-not-found ${DEMO_OCI_REGISTRY}
}

__remove_image_registry_nfs_share() {
    if [[ -d ${DEMO_OCI_REGISTRY_DATA_NFS_DIR} ]]
    then
        echo "Removing ${DEMO_OCI_REGISTRY_DATA_NFS_DIR} and delisting it as an NFS share"

        sudo rm -rf ${DEMO_OCI_REGISTRY_DATA_NFS_DIR}
        sudo bash -c "cat /etc/exports | grep -v ${DEMO_OCI_REGISTRY_DATA_NFS_DIR} > /etc/exports || :"
        sudo exportfs -a
        sudo systemctl restart nfs-server.service
    else
        echo "${DEMO_OCI_REGISTRY_DATA_NFS_DIR} not found.  Skipping..."
    fi
}

__remove_whitelisted_image_registry_host_names() {
    local _IMAGE_OCI_REGISTRYS_LIST=(${DEV_ENV} ${HOTFIX_ENV} $(echo ${TEST_ENVS} | sed 's/:/ /g') ${PRE_PROD_ENV} ${PROD_ENV})
    local _IMAGE_OCI_REGISTRYS=''
    for REPO in ${_IMAGE_OCI_REGISTRYS_LIST[@]}
    do
        _IMAGE_OCI_REGISTRYS="${_IMAGE_OCI_REGISTRYS} $(eval echo \${${REPO}${REGISTRY_USERNAME_POSTFIX}})"
    done
    _IMAGE_OCI_REGISTRYS=$(echo ${_IMAGE_OCI_REGISTRYS} | xargs -n1 | sort -u | xargs)

    echo
    for REGISTRY_NAME in ${_IMAGE_OCI_REGISTRYS}
    do
        _HOST_DOMAIN=${REGISTRY_NAME}-${DEMO_OCI_REGISTRY}.${CLUSTER_WILDCARD_DOMAIN}

        echo "Removing whitelisted ${_HOST_DOMAIN} as an insecure image registry."
        oc get image.config.openshift.io/cluster -o yaml | grep -v ${_HOST_DOMAIN} | oc apply -f -
    done
}

__delete_remote_el_cicd_git_repos() {
    echo
    local _ALL_EL_CICD_DIRS=$(echo "${EL_CICD_REPO_DIRS}:${EL_CICD_DOCS_REPO}:${EL_CICD_TEST_PROJECTS}" | tr ':' ' ')
    for GIT_REPO_DIR in ${_ALL_EL_CICD_DIRS}
    do
        __remove_git_repo ${GIT_REPO_DIR}
    done
}

__remove_git_repo() {
    GIT_REPO_DIR=${1}

    local _PAT=${EL_CICD_GIT_REPO_ACCESS_TOKEN:-$(cat ${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE})}

    local _REMOTE_GIT_DIR_EXISTS=$(curl -s -o /dev/null -w "%{http_code}" -u :${_PAT} \
        ${GITHUB_REST_API_HDR}  \
        https://${EL_CICD_GIT_API_URL}/repos/${EL_CICD_ORGANIZATION}/${GIT_REPO_DIR})
    if [[ ${_REMOTE_GIT_DIR_EXISTS} == 200 ]]
    then
        curl -X DELETE -sIL -o /dev/null -u :${_PAT} \
            ${GITHUB_REST_API_HDR}  https://${EL_CICD_GIT_API_URL}/repos/${EL_CICD_ORGANIZATION}/${GIT_REPO_DIR}
        echo "${GIT_REPO_DIR} deleted from Git host ${EL_CICD_GIT_DOMAIN}/${EL_CICD_ORGANIZATION}/${GIT_REPO_DIR}"
    else
        echo "${GIT_REPO_DIR} NOT FOUND on Git host, or the Access Token has expired. Skipping..."
    fi
}