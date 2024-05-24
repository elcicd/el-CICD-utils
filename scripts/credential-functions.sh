#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

export GITHUB_REST_API_HDR='Accept: application/vnd.github.v3+json'

_verify_git_secret_files_exist() {
    if [[ ! -f ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE} ||
          ! -f ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE}.pub ||
          ! -f ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE} ||
          ! -f ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE}.pub ||
          ! -f ${EL_CICD_OCI_SECRETS_FILE} ||
          ! -f ${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE} ]]
    then
        echo
        echo "ERROR:"
        echo "  MISSING ONE OR MORE OF THE FOLLOWING CREDENTIALS FILES:"
        echo "    ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}"
        echo "    ${EL_CICD_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}.pub"
        echo "    ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}"
        echo "    ${EL_CICD_CONFIG_SSH_READ_ONLY_DEPLOY_KEY_FILE//${SECRET_FILE_DIR}\//}.pub"
        echo "    ${EL_CICD_OCI_SECRETS_FILE//${SECRET_FILE_DIR}\//}"
        echo "    ${EL_CICD_GIT_ADMIN_ACCESS_TOKEN_FILE//${SECRET_FILE_DIR}\//}"

        __verify_continue
    fi
}

_get_oci_registry_ids() {
    local _OCI_REGISTRY_IDS="${JENKINS} "
    if [[ ${EL_CICD_MASTER_NONPROD} == ${_TRUE} ]]
    then
        _OCI_REGISTRY_IDS+="${DEV_ENV} ${HOTFIX_ENV} ${TEST_ENVS/:/ } "
    fi

    _OCI_REGISTRY_IDS+="${PRE_PROD_ENV} "

    if [[ ${EL_CICD_MASTER_PROD} == ${_TRUE} ]]
    then
        _OCI_REGISTRY_IDS+="${PROD_ENV} "
    fi

    echo ${_OCI_REGISTRY_IDS}
}

_verify_oci_registry_secrets() {
    local _OCI_REGISTRY_IDS=$(_get_oci_registry_ids)

    for OCI_REGISTRY_ID in ${_OCI_REGISTRY_IDS@L}
    do
        _oci_registry_login ${OCI_REGISTRY_ID}
    done
}

__verify_continue() {
    echo
    echo "IT IS STRONGLY ADVISED YOU EXIT, FIX THE PROBLEM, AND RE-RUN THE UTILITY"
    echo
    CONTINUE=$(_get_yes_no_answer 'ARE YOU SURE YOU WISH TO CONTINUTE? [Y/n] ')
    if [[ ${CONTINUE} != ${_YES} ]]
    then
        exit 1
    fi
}

_check_upgrade_install_sealed_secrets() {
    _collect_sealed_secret_info

    _confirm_upgrade_install_sealed_secrets

    if [[ ${INSTALL_SEALED_SECRETS} == ${_YES} ]]
    then
        _install_sealed_secrets
    else
        exit 0
    fi
}

_collect_sealed_secret_info() {
    echo
    HAS_SEALED_SECRETS=$(helm list --short --filter 'sealed-secrets' -n kube-system)
    if [[ ${HAS_SEALED_SECRETS} ]]
    then
        echo "CURRENTLY INSTALLED SEALED SECRETS VERSION INFO:"
        helm list --filter 'sealed-secrets' --time-format "2006-01-02" -n kube-system
    else
        echo 'NO CURRENTLY INSTALLED SEALED SECRETS VERSION FOUND: Use the --sealed-secrets flag to install.'
    fi

    local _SS_URL='https://bitnami-labs.github.io/sealed-secrets'
    if [[ -z ${SEALED_SECRETS_CHART_VERSION} ]]
    then
        SEALED_SECRETS_CHART_VERSION=$(helm show chart sealed-secrets --repo ${_SS_URL} | grep version | tr -d 'version: ')
    fi

    SEALED_SECRETS_RELEASE_VERSION=$(helm show chart sealed-secrets --version ${SEALED_SECRETS_CHART_VERSION} --repo ${_SS_URL} | grep appVersion)
    SEALED_SECRETS_RELEASE_VERSION=$(echo ${SEALED_SECRETS_RELEASE_VERSION} | tr -d 'appVersion: ')
    SEALED_SECRETS_RELEASE_INFO="Helm Chart ${SEALED_SECRETS_CHART_VERSION} / Release ${SEALED_SECRETS_RELEASE_VERSION}"
}

_confirm_upgrade_install_sealed_secrets() {
    echo
    echo "SEALED SECRETS VERSION TO BE INSTALLED: ${SEALED_SECRETS_RELEASE_INFO}"

    echo
    local _MSG="Do you wish to install/upgrade sealed-secrets and kubeseal to ${SEALED_SECRETS_RELEASE_INFO}? [Y/n] "
    INSTALL_SEALED_SECRETS=$(_get_yes_no_answer "${_MSG}")
}

_install_sealed_secrets() {
    set -e

    echo
    echo '================= SEALED SECRETS ================='
    echo
    echo "Installing Sealed Secrets ${_BOLD}${SEALED_SECRETS_RELEASE_INFO}${_REGULAR}"
    echo
    helm upgrade --install --atomic --history-max=2 \
                 --repo https://bitnami-labs.github.io/sealed-secrets \
                 --version ${SEALED_SECRETS_CHART_VERSION} \
                 -n kube-system \
                 sealed-secrets sealed-secrets
    echo
    echo '================= SEALED SECRETS ================='

    echo
    echo 'Downloading and copying kubeseal to /usr/local/bin for generating Sealed Secrets.'
    local _SEALED_SECRETS_DIR=/tmp/sealedsecrets
    mkdir -p ${_SEALED_SECRETS_DIR}
    local _KUBESEAL_URL="https://github.com/bitnami-labs/sealed-secrets/releases/download"
    _KUBESEAL_URL="${_KUBESEAL_URL}/v${SEALED_SECRETS_RELEASE_VERSION:?}/kubeseal-${SEALED_SECRETS_RELEASE_VERSION:?}-linux-amd64.tar.gz"
    sudo rm -f ${_SEALED_SECRETS_DIR}/kubeseal* /usr/local/bin/kubeseal
    wget -qc --force-progress ${_KUBESEAL_URL} -O ${_SEALED_SECRETS_DIR}/kubeseal.tar.gz
    tar -xvzf ${_SEALED_SECRETS_DIR}/kubeseal.tar.gz -C ${_SEALED_SECRETS_DIR}
    sudo install -m 755 ${_SEALED_SECRETS_DIR}/kubeseal /usr/local/bin/kubeseal

    set +e
}

_oci_registry_login() {
    OCI_REGISTRY_ID=${1}

    local _OCI_USERNAME=$(_get_oci_username ${OCI_REGISTRY_ID})
    local _OCI_PASSWORD=$(_get_oci_password ${OCI_REGISTRY_ID})
    local _OCI_REGISTRY=${OCI_REGISTRY_ID@U}${OCI_REGISTRY_POSTFIX}
    local _ENABLE_TLS=${OCI_REGISTRY_ID@U}${OCI_ENABLE_TLS_POSTFIX}

    if [[ "${_OCI_USERNAME}" && "${_OCI_PASSWORD}" && "${!_ENABLE_TLS}" && "${!_OCI_REGISTRY}" ]]
    then
        set -e
        echo
        echo -n "LOGIN TO ${OCI_REGISTRY_ID@U} OCI REGISTRY [${!_OCI_REGISTRY}]: "
        echo ${_OCI_PASSWORD} | podman login ${!_OCI_REGISTRY} --tls-verify=${!_ENABLE_TLS} -u ${_OCI_USERNAME} --password-stdin
        set +e
    else
        PARENT_DIR=$(basename $(dirname ${EL_CICD_OCI_SECRETS_FILE}))
        echo "ERROR: ONE OF THE FOLLOWING NOT DEFINED FOR THE ${OCI_REGISTRY_ID@U} OCI REGISTRY:"
        echo "- USERNAME/PASSWORD in ${PARENT_DIR}/$(basename ${EL_CICD_OCI_SECRETS_FILE})"
        echo "- OCI_REGISTRY: ${!_OCI_REGISTRY}"
        exit 1
    fi
}

_oci_helm_registry_login() {
    local _HELM_REGISTRY_USERNAME=$(_get_oci_username ${HELM})
    local _HELM_REGISTRY_PASSWORD=$(_get_oci_password ${HELM})

    if [[ EL_CICD_HELM_OCI_REGISTRY_ENABLE_TLS == ${_FALSE} ]]
    then
        local _TLS_INSECURE='--insecure'
    fi

    if [[ "${_HELM_REGISTRY_USERNAME}" && "${_HELM_REGISTRY_PASSWORD}" ]]
    then
        set -e
        echo
        echo -n "LOGIN TO HELM OCI REGISTRY [${EL_CICD_HELM_OCI_REGISTRY_DOMAIN}]: "
        echo ${_HELM_REGISTRY_PASSWORD} | \
            helm registry login ${EL_CICD_HELM_OCI_REGISTRY_DOMAIN} ${_TLS_INSECURE} -u ${_HELM_REGISTRY_USERNAME} --password-stdin
        set +e
    else
        PARENT_DIR=$(basename $(dirname ${EL_CICD_OCI_SECRETS_FILE}))
        echo "ERROR: ONE OF THE FOLLOWING NOT DEFINED FOR THE HELM OCI REGISTRY:"
        echo "- USERNAME/PASSWORD in ${PARENT_DIR}/$(basename ${EL_CICD_OCI_SECRETS_FILE})"
        echo "- EL_CICD_HELM_OCI_REGISTRY_DOMAIN: ${EL_CICD_HELM_OCI_REGISTRY_DOMAIN}"
        exit 1
    fi
}

_refresh_team_credentials() {
    __trigger_refresh_projects false false false true
}

_refresh_team_servers() {
    __trigger_refresh_projects true true true true
}

__trigger_refresh_projects() {
    BUILD_PARAMETERS="REFRESH_TEAM_SERVERS=${1}"
    BUILD_PARAMETERS+="&REFRESH_PIPELINES=${2}"
    BUILD_PARAMETERS+="&REFRESH_SDLC_ENVIRONMENTS=${3}"
    BUILD_PARAMETERS+="&REFRESH_CREDENTIALS=${4}"
    BUILD_PARAMETERS+="&CONFIRM_BEFORE_REFRESHING=false"
    set -e

    local TOKEN_NAME=$(oc get secret ${JENKINS_REMOTE_SERVICE_ACCOUNT} -o name -n ${EL_CICD_MASTER_NAMESPACE})
    local TOKEN=$(oc get ${TOKEN_NAME} -n ${EL_CICD_MASTER_NAMESPACE} -o jsonpath='{.data.token}' | base64 --decode)

    local AUTH_BEARER="Authorization:Bearer ${TOKEN}"
    local REFRESH_URL="https://jenkins-${EL_CICD_MASTER_NAMESPACE}.${CLUSTER_WILDCARD_DOMAIN}/job/refresh-projects/buildWithParameters"

    curl -ksSL -X POST --fail-with-body -w '%{http_code}\n' -o /dev/null -H "${AUTH_BEARER}" ${REFRESH_URL}?${BUILD_PARAMETERS}
    set +e
}

_oci_jenkins_registry_login() {
    _oci_registry_login ${JENKINS}
}

_get_oci_username() {
    jq -r ".${1}.username | select (.!=null)" ${EL_CICD_OCI_SECRETS_FILE}
}

_get_oci_password() {
    jq -r ".${1}.password | select (.!=null)" ${EL_CICD_OCI_SECRETS_FILE}
}