#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_bootstrap_lab_environment() {
    set -eE

    echo
    echo "${DEV_SETUP_WELCOME_MSG}"

    __gather_lab_setup_info

    __summarize_and_confirm_lab_setup_info

    __set_config_value CLUSTER_WILDCARD_DOMAIN ${CLUSTER_WILDCARD_DOMAIN} "${ROOT_CONFIG_FILE}"

    if [[ ${SETUP_CRC} == ${_YES} ]]
    then
        __bootstrap_clean_crc

        __additional_cluster_config
    fi

    CRC_EXEC=$(find ${EL_CICD_HOME} -name crc)
    if [[ "${CRC_EXEC}" ]]
    then
        if [[ $(${CRC_EXEC} status -o json | jq -r .crcStatus) != "Running" ]]
        then
            _start_crc
        fi
        eval $(${CRC_EXEC} oc-env)
    fi

    if [[ ${INSTALL_REGISTRY} == ${_YES} ]]
    then
        _remove_image_registry
        oc wait --for=delete namespace/${DEMO_OCI_REGISTRY}

        __setup_image_registries
    fi

    if [[ ${RECREATE_GIT_ADMIN_ACCESS_TOKEN_FILE} == ${_YES} ]]
    then
        __create_credentials
    fi

    if [[ ${CREATE_GIT_REPOS} == ${_YES} && ${EL_CICD_ORGANIZATION} != ${DEFAULT_EL_CICD_ORGANIZATION_NAME} ]]
    then
        __init_el_cicd_repos
    fi

    echo
    echo "el-CICD Development environment setup complete."
}

__gather_lab_setup_info() {
    echo
    CRC_TAR_XZ=$(ls ${EL_CICD_HOME}/crc-*.tar.xz 2>/dev/null | wc -l)
    if [[ ${CRC_TAR_XZ} -gt 0 && -f "${EL_CICD_HOME}/pull-secret.txt" ]]
    then
        SETUP_CRC=$(_get_yes_no_answer 'Do you wish to setup OpenShift Local? [Y/n] ')
    else
        echo 'WARNING: OpenShift Local tar.xz and/or pull-secret.txt not found in el-CICD home directory.  Skipping...'
    fi

    if [[ ${SETUP_CRC} != ${_YES} ]]
    then
        read -p 'Enter Cluster wildcard domain (leave blank if using a currently running OpenShift Local instance): ' TEMP_CLUSTER_WILDCARD_DOMAIN
        CLUSTER_WILDCARD_DOMAIN=${TEMP_CLUSTER_WILDCARD_DOMAIN:-${CLUSTER_WILDCARD_DOMAIN}}

        _confirm_logged_into_cluster
    fi

    echo
    INSTALL_REGISTRY=$(_get_yes_no_answer 'Do you wish to install the development image registry on your cluster? [Y/n] ')
    if [[ ${INSTALL_REGISTRY} == ${_YES} ]]
    then
        SETUP_REGISTRY_NFS=$(_get_yes_no_answer 'Do you wish to setup an NFS share for your image registry (only needed for developers)? [Y/n] ')

        if [[ ${SETUP_REGISTRY_NFS} == ${_YES} ]]
        then
            read -s -p "Sudo credentials required: " SUDO_PWD

            printf "%s\n" "${SUDO_PWD}" | sudo -p '' -S echo 'verified'
        fi
    else
        echo 'IF NOT ALREADY DONE, proper values for your chosen image registry must be set in the el-CICD configuration files.'
        echo 'See el-CICD operational documentation for information on how to configure the image registry values per CICD environment.'
    fi

    echo
    if [[ -f ${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE} ]]
    then
        RECREATE_GIT_ADMIN_ACCESS_TOKEN_FILE=$(_get_yes_no_answer 'Do you wish to (re)create the Git admin access token file? [Y/n] ')
    else
        RECREATE_GIT_ADMIN_ACCESS_TOKEN_FILE=${_YES}
    fi

    if [[ ${EL_CICD_ORGANIZATION} != ${DEFAULT_EL_CICD_ORGANIZATION_NAME} ]]
    then
        echo
        CREATE_GIT_REPOS=$(_get_yes_no_answer 'Do you wish to create and push the el-CICD Git repositories? [Y/n] ')

        if [[ ${CREATE_GIT_REPOS} == ${_YES} && ${EL_CICD_ORGANIZATION} != ${DEFAULT_EL_CICD_ORGANIZATION_NAME} ]]
        then
            echo
            local _HOST_DOMAIN='github.com'
            echo 'NOTE: Only GitHub is supported as a remote host.'
            read -p "Enter Git host domain (default's to '${_HOST_DOMAIN}' if left blank): " GIT_HOST_DOMAIN
            GIT_HOST_DOMAIN=${GIT_HOST_DOMAIN:-${_HOST_DOMAIN}}

            local _API_DOMAIN='api.github.com'
            read -p "Enter Git host REST API domain (default's to '${_API_DOMAIN}' if left blank): " GIT_API_DOMAIN
            GIT_API_DOMAIN=${GIT_API_DOMAIN:-${_API_DOMAIN}}

            read -p "Enter Git user/organization: " EL_CICD_ORGANIZATION
            if [[ -z ${EL_CICD_ORGANIZATION} ]]
            then
                echo "ERROR: MUST ENTER A GIT USER"
                exit 1
            fi
            read -p "Enter Git user/organization email: " EL_CICD_ORGANIZATION_EMAIL
        fi
    fi

    if [[ ${RECREATE_GIT_ADMIN_ACCESS_TOKEN_FILE} == ${_YES} || ${CREATE_GIT_REPOS} == ${_YES} ]]
    then
        if [[ ${RECREATE_GIT_ADMIN_ACCESS_TOKEN_FILE} == ${_YES} ]]
        then
            read -s -p "Enter Git host personal access token for ${EL_CICD_ORGANIZATION}:" GIT_ACCESS_TOKEN
            echo
        else
            GIT_ACCESS_TOKEN=$(cat ${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE})
        fi

        local _TOKEN_TEST_RESULT=$(curl -sL -u :${GIT_ACCESS_TOKEN} https://${EL_CICD_GIT_API_URL}/user | jq -r '.login')
        if [[ -z ${_TOKEN_TEST_RESULT} ]]
        then
            echo "ERROR: INVALID GIT TOKEN"
            echo "A valid git personal access token for [${EL_CICD_ORGANIZATION}] must be provided when generating credentials and/or Git repositories"
            echo "EXITING..."
            exit 1
        else
            echo "Git token verified."
        fi
    fi
}

__summarize_and_confirm_lab_setup_info() {
    echo
    echo "${_BOLD}===================== ${_BOLD}SUMMARY${_REGULAR} =====================${_REGULAR}"
    echo

    if [[ ${SETUP_CRC} == ${_YES} ]]
    then
        echo "OpenShift Local ${_BOLD}WILL${_REGULAR} be setup.  Initial login to kubeadmin will be automated."
    else
        echo "OpenShift Local will ${_BOLD}NOT${_REGULAR} be setup."
    fi

    echo
    echo "Cluster wildcard Domain: ${_BOLD}*.${CLUSTER_WILDCARD_DOMAIN}${_REGULAR}"

    echo
    if [[ ${INSTALL_REGISTRY} == ${_YES} ]]
    then
        echo -n "An image registry ${_BOLD}WILL${_REGULAR} be installed on your cluster ${_BOLD}WITH"
        if [[ ${SETUP_REGISTRY_NFS} != ${_YES} ]]
        then
            echo -n "OUT"
        fi
        echo "${_REGULAR} an NFS share."
    else
        echo "An image registry will ${_BOLD}NOT${_REGULAR} be installed on your cluster."
    fi

    echo
    if [[ ${RECREATE_GIT_ADMIN_ACCESS_TOKEN_FILE} == ${_YES} ]]
    then
        echo "The Git admin token file ${_BOLD}WILL${_REGULAR} be (re)created."
    else
        echo "The Git admin token file will ${_BOLD}NOT${_REGULAR} be (re)created."
    fi

    echo
    if [[ ${CREATE_GIT_REPOS} == ${_YES} ]]
    then
        echo "el-CICD Git repositories ${_BOLD}WILL${_REGULAR} be intialized and pushed to ${EL_CICD_ORGANIZATION} at ${GIT_API_DOMAIN} if necessary."
    else
        echo "el-CICD Git repositories will ${_BOLD}NOT${_REGULAR} be intialized."
    fi

    echo
    echo "${_BOLD}=================== ${_BOLD}END SUMMARY${_REGULAR} ===================${_REGULAR}"

    _confirm_continue
}

__bootstrap_clean_crc() {
    _remove_existing_crc

    echo
    echo "Extracting OpenShift Local tar.xz to ${EL_CICD_HOME}"
    tar -xf ${EL_CICD_HOME}/crc-linux-amd64.tar.xz -C ${EL_CICD_HOME}

    CRC_EXEC=$(find ${EL_CICD_HOME} -name crc)

    echo
    ${CRC_EXEC} setup <<< 'y'

   _start_crc
}

_start_crc() {
    CRC_EXEC=${CRC_EXEC:-$(find ${EL_CICD_HOME} -name crc)}
    local _CICD_PASSWORD=elcicd
    if [[ -z $(${CRC_EXEC} status | grep Started) ]]
    then
        echo
        echo "Starting OpenShift Local with ${CRC_V_CPU} vCPUs, ${CRC_MEMORY}Mi memory, ${CRC_DISK}Gi disk, cluster monitoring ${CRC_CLUSTER_MONITORING:-false}"
        echo "kubeadmin password is '${_CICD_PASSWORD}'"
        echo
        ${CRC_EXEC} config set kubeadmin-password ${_CICD_PASSWORD}
        ${CRC_EXEC} config set enable-cluster-monitoring ${CRC_CLUSTER_MONITORING:-false}
        ${CRC_EXEC} config set cpus ${CRC_V_CPU}
        ${CRC_EXEC} config set memory ${CRC_MEMORY}
        ${CRC_EXEC} config set disk-size ${CRC_DISK}
        ${CRC_EXEC} start -p ${EL_CICD_HOME}/pull-secret.txt

        eval $(${CRC_EXEC} oc-env)
        source <(oc completion ${CRC_SHELL})
    else
        echo 'crc exec not found; exiting'
        exit 1
    fi
}

__additional_cluster_config() {
    echo
    if [[ -z $(oc get secrets -n kube-system | grep sealed-secrets-key) ]]
    then
        echo "Creating Sealed Secrets master key"
        oc create -f ${EL_CICD_SCRIPTS_RESOURCES_DIR}/test-projects-sealed-secrets-master.key
        oc delete pod --ignore-not-found -n kube-system -l name=sealed-secrets-controller
    else
        echo "Sealed Secrets master key found. Apply manually if still required.  Skipping..."
    fi
}

__create_image_registry_nfs_share() {
    echo
    if [[ ! -d ${DEMO_OCI_REGISTRY_DATA_NFS_DIR} || -z $(cat /etc/exports | grep ${DEMO_OCI_REGISTRY_DATA_NFS_DIR}) ]]
    then
        echo "Creating NFS share on host for developer image registries, if necessary: ${DEMO_OCI_REGISTRY_DATA_NFS_DIR}"
        if [[ -z $(cat /etc/exports | grep ${DEMO_OCI_REGISTRY_DATA_NFS_DIR}) ]]
        then
            printf "%s\n" "${SUDO_PWD}" | sudo -E --stdin bash -c "echo '${DEMO_OCI_REGISTRY_DATA_NFS_DIR} *(rw,sync,all_squash,insecure)' | sudo tee -a /etc/exports"
        fi

        printf "%s\n" "${SUDO_PWD}" | sudo --stdin mkdir -p ${DEMO_OCI_REGISTRY_DATA_NFS_DIR}
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin chown -R nobody:nobody ${DEMO_OCI_REGISTRY_DATA_NFS_DIR}
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin chmod 777 ${DEMO_OCI_REGISTRY_DATA_NFS_DIR}
        printf "%s\n" "${SUDO_PWD}" | sudo firewall-cmd --permanent --add-service=nfs --zone=libvirt
        printf "%s\n" "${SUDO_PWD}" | sudo firewall-cmd --permanent --add-service=mountd --zone=libvirt
        printf "%s\n" "${SUDO_PWD}" | sudo firewall-cmd --permanent --add-service=rpc-bind --zone=libvirt
        printf "%s\n" "${SUDO_PWD}" | sudo firewall-cmd --reload
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin exportfs -a
        printf "%s\n" "${SUDO_PWD}" | sudo --stdin systemctl restart nfs-server.service
    else
        echo "Developer image registries' NFS Share found.  Skipping..."
    fi
}

__setup_image_registries() {
    set -e

    local _REGISTRY_NAMES=$(echo ${DEMO_OCI_REGISTRY_NAMES} | tr ':' ' ')
    for REGISTRY_NAME in ${_REGISTRY_NAMES}
    do
        local _OBJ_NAME=${REGISTRY_NAME}-${DEMO_OCI_REGISTRY}
        local _OBJ_NAMES=${_OBJ_NAMES:+${_OBJ_NAMES},}${_OBJ_NAME}
        local _HTPASSWD=$(htpasswd -Bbn elcicd${REGISTRY_NAME} ${DEMO_OCI_REGISTRY_USER_PWD})
        local _HTPASSWDS="${_HTPASSWDS:+${_HTPASSWDS} } --set-string elCicdDefs-htpasswd.${_OBJ_NAME}_HTPASSWD=${_HTPASSWD}"
    done
    local _PROFILES='htpasswd'
    if [[ ${SETUP_REGISTRY_NFS} == ${_YES} ]]
    then
        __create_image_registry_nfs_share
        _PROFILES+=",nfs"
    fi

    DEMO_OCI_REGISTRY_HOST_IP=$(ip route get 1 | awk '{print $(NF-2);exit}')
    echo
    set -x
    helm upgrade --install --atomic --create-namespace --history-max=1 \
        --set-string elCicdProfiles="{${_PROFILES}}" \
        --set-string elCicdDefs.OBJ_NAMES="{${_OBJ_NAMES}}" \
        --set-string elCicdDefs.HOST_IP=${DEMO_OCI_REGISTRY_HOST_IP} \
        --set-string elCicdDefs.DEMO_OCI_REGISTRY=${DEMO_OCI_REGISTRY} \
        ${_HTPASSWDS} \
        --set-string elCicdDefaults.ingressHostDomain=${CLUSTER_WILDCARD_DOMAIN} \
        -f ${EL_CICD_DIR}/${DEMO_CHART_DEPLOY_DIR}/demo-image-registry-values.yaml \
        -n ${DEMO_OCI_REGISTRY} \
        ${DEMO_OCI_REGISTRY} \
        ${EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart
    set +x

    __register_insecure_registries

    echo
    echo 'Docker Registry is up!'
    sleep 2
    set +e
}

__register_insecure_registries() {
    echo
    if [[ -z $(oc describe image.config.openshift.io/cluster | grep 'Insecure Registries') ]]
    then
        echo "Adding array for whitelisting insecure registries."
        oc patch image.config.openshift.io/cluster --type=merge -p='{"spec":{"registrySources":{"insecureRegistries":[]}}}'
    else
        echo "Array for whitelisting insecure image registries already exists.  Skipping..."
    fi

    local _REGISTRY_NAMES=$(echo ${DEMO_OCI_REGISTRY_NAMES} | tr ':' ' ')
    for REGISTRY_NAME in ${_REGISTRY_NAMES}
    do
        local _HOST_DOMAIN=${REGISTRY_NAME}-${DEMO_OCI_REGISTRY}.${CLUSTER_WILDCARD_DOMAIN}

        oc get image.config.openshift.io/cluster -o yaml | grep -v "\- ${_HOST_DOMAIN}" | oc apply -f -

        echo "Whitelisting ${_HOST_DOMAIN} as an insecure image registry."
        oc patch image.config.openshift.io/cluster --type=json \
            -p='[{"op": "add", "path": "/spec/registrySources/insecureRegistries/-", "value": "'"${_HOST_DOMAIN}"'" }]'
    done
}

__create_credentials() {
    mkdir -p ${SECRET_FILE_DIR}

    echo
    echo "Creating ${EL_CICD_ORGANIZATION} access token file: ${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE}"
    echo ${GIT_ACCESS_TOKEN} > ${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE}

    local _CICD_ENVIRONMENTS="${DEV_ENV} ${HOTFIX_ENV} $(echo ${TEST_ENVS} | sed 's/:/ /g') ${PRE_PROD_ENV} ${PROD_ENV}"
    for ENV in ${_CICD_ENVIRONMENTS}
    do
        echo
        echo "Creating the image repository access token file for ${ENV} environment:"
        echo ${ENV@L}${REGISTRY_CREDENTIALS_POSTFIX}
        echo ${DEMO_OCI_REGISTRY_USER_PWD} > ${ENV@L}${REGISTRY_CREDENTIALS_POSTFIX}
    done
}

__init_el_cicd_repos() {
    __set_config_value EL_CICD_ORGANIZATION ${EL_CICD_ORGANIZATION} "${EL_CICD_CONFIG_DIR}/${ROOT_CONFIG_FILE}"
    __set_config_value EL_CICD_GIT_DOMAIN ${GIT_HOST_DOMAIN} "${EL_CICD_CONFIG_DIR}/${ROOT_CONFIG_FILE}"
    __set_config_value EL_CICD_GIT_API_URL ${GIT_API_DOMAIN} "${EL_CICD_CONFIG_DIR}/${ROOT_CONFIG_FILE}"

    find ${EL_CICD_CONFIG_DIR}/project-defs/*.yml -type f -exec sed -i "s/gitOrganization:.*$/gitOrganization: ${EL_CICD_ORGANIZATION}/" {} \;
    find ${EL_CICD_CONFIG_DIR}/project-defs/*.yml -type f -exec sed -i "s/gitHost:.*$/gitHost: ${GIT_HOST_DOMAIN}/" {} \;
    find ${EL_CICD_CONFIG_DIR}/project-defs/*.yml -type f -exec sed -i "s/gitRestApiHost:.*$/gitRestApiHost: ${GIT_API_DOMAIN}/" {} \;

    local _ALL_EL_CICD_REPOS=$(echo "${EL_CICD_REPO} ${EL_CICD_CONFIG_REPO} ${EL_CICD_DEPLOY_REPO} ${EL_CICD_DOCS_REPO} ${EL_CICD_TEST_PROJECTS}")
    for EL_CICD_REPO_dir in ${_ALL_EL_CICD_REPOS}
    do
        __create_git_repo ${EL_CICD_REPO_dir}
    done
}

__create_git_repo() {
    local _GIT_REPO_DIR=${1}

    echo
    echo "CREATING REMOTE REPOSITORY: ${_GIT_REPO_DIR}"

    local _GIT_COMMAND="git -C ${EL_CICD_HOME}/${_GIT_REPO_DIR}"
    if [[ ! -d ${EL_CICD_HOME}/${_GIT_REPO_DIR}/.git ]]
    then
        git init ${EL_CICD_HOME}/${_GIT_REPO_DIR}
        ${_GIT_COMMAND} add -A
        ${_GIT_COMMAND} commit -am 'Initial commit of el-CICD repositories by bootstrap script'
        ${_GIT_COMMAND} config --global user.name ${EL_CICD_ORGANIZATION}
        ${_GIT_COMMAND} config --global user.email ${EL_CICD_ORGANIZATION_EMAIL}
    else
        echo "Repo ${_GIT_REPO_DIR} already initialized.  Skipping..."
    fi

    __create_remote_github_repo ${_GIT_REPO_DIR}

    ${_GIT_COMMAND} remote add origin git@${GIT_HOST_DOMAIN}:${EL_CICD_ORGANIZATION}/${_GIT_REPO_DIR}.git
    ${_GIT_COMMAND} checkout -b  ${EL_CICD_GIT_REPO_BRANCH_NAME}

    ${_GIT_COMMAND} \
        -c credential.helper="!creds() { echo password=${GIT_ACCESS_TOKEN}; }; creds" \
        push -u origin ${EL_CICD_GIT_REPO_BRANCH_NAME}
}

__create_remote_github_repo() {
    local _GIT_REPO_DIR=${1}

    local _GIT_JSON_POST=$(jq -n --arg GIT_REPO_DIR "${_GIT_REPO_DIR}" '{"name":$GIT_REPO_DIR}')

    REMOTE_GIT_DIR_EXISTS=$(curl -sL -o /dev/null -w "%{http_code}" -X POST \
        -u :${GIT_ACCESS_TOKEN} \
        ${GITHUB_REST_API_HDR}  \
        https://${GIT_API_DOMAIN}/user/repos \
        -d "${_GIT_JSON_POST}")
    if [[ ${REMOTE_GIT_DIR_EXISTS} == 201 ]]
    then
        echo "Created ${EL_CICD_ORGANIZATION}/${_GIT_REPO_DIR} at ${GIT_API_DOMAIN}"
    else
        echo "ERROR: DID NOT create ${EL_CICD_ORGANIZATION}/${_GIT_REPO_DIR} at ${GIT_API_DOMAIN}"
        echo "Check your Git credentials and whether the repo already exists."
    fi
}


__set_config_value(){
    local _KEY=${1}
    local _NEW_VALUE=${2}
    local _FILE=${3}

    sed -i -e "/${_KEY}=/ s/=.*/=${_NEW_VALUE}/" ${_FILE}
}