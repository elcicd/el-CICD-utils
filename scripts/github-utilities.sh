#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

GITHUB_REST_API_ACCEPT_HEADER="Accept: application/vnd.github+json"
GITHUB_REST_API_VERSION_HEADER="X-GitHub-Api-Version: 2022-11-28"

GITHUB_DEPLOY_KEY_JSON='
{
    "title": "%DEPLOY_KEY_TITLE%",
    "key": "%DEPLOY_KEY%",
    "read_only": %READ_ONLY%
}'

GITHUB_WEBHOOK_JSON='
{
  "name": "web",
  "active": true,
  "events": [
    "push",
    "pull_request"
  ],
  "config": {
    "url": "%JENKINS_WEBOOK_URL%",
    "content_type": "json",
    "insecure_ssl": "1"
  }
}'

CURL_COMMAND='curl --retry 9 --max-time 10 --retry-delay 0 --retry-max-time 90 --retry-all-errors -ksSL --fail-with-body'

EL_CICD_TMP_PREFIX='/tmp/tmp.elcicd'

__configure_github_headers() {
    __GITHUB_HEADERS=(-H "Authorization: Bearer ${1}" -H "${GITHUB_REST_API_ACCEPT_HEADER}" -H "${GITHUB_REST_API_VERSION_HEADER}")
}

_delete_git_repo_deploy_key() {
    local _GITHUB_API_HOST=${1}
    local _GITHUB_ORG=${2}
    local _REPO_NAME=${3}
    local _GIT_ACCESS_TOKEN=${4}
    local _DEPLOY_KEY_TITLE=${5}

    __configure_github_headers ${_GIT_ACCESS_TOKEN}
    local _EL_CICD_GITHUB_KEYS_URL="https://${_GITHUB_API_HOST}/repos/${_GITHUB_ORG}/${_REPO_NAME}/keys"

    local _KEY_IDS=$(${CURL_COMMAND} "${__GITHUB_HEADERS[@]}" ${_EL_CICD_GITHUB_KEYS_URL} | jq ".[] | select(.title  == \"${_DEPLOY_KEY_TITLE}\") | .id" 2>/dev/null)

    echo
    if [[ ${_KEY_IDS} ]]
    then
        for KEY_ID in ${_KEY_IDS}
        do
            ${CURL_COMMAND} -X DELETE "${__GITHUB_HEADERS[@]}" ${_EL_CICD_GITHUB_KEYS_URL}/${KEY_ID} | jq 'del(.key)'
            echo "DELETED DEPLOY KEY ${KEY_ID} FROM ${_GITHUB_ORG}/${_REPO_NAME}"
        done
    else
        echo "NO DEPLOY KEYS FOUND FOR ${_GITHUB_ORG}/${_REPO_NAME}: SKIPPING"
    fi
}

_add_git_repo_deploy_key() {
    local _GITHUB_API_HOST=${1}
    local _GITHUB_ORG=${2}
    local _REPO_NAME=${3}
    local _GIT_ACCESS_TOKEN=${4}
    local _DEPLOY_KEY_TITLE=${5}
    local _DEPLOY_KEY_FILE="${6}.pub"

    # READ_ONLY *MUST* be 'false' (case insensitive) to push a read/write key
    local _READ_ONLY=${7}
    if [[ ${_READ_ONLY@L} != 'false' ]]
    then
        _READ_ONLY=true
    fi

    __configure_github_headers ${_GIT_ACCESS_TOKEN}

    CURRENT_DEPLOY_KEY_JSON=${GITHUB_DEPLOY_KEY_JSON/\%DEPLOY_KEY_TITLE%/${_DEPLOY_KEY_TITLE}}
    CURRENT_DEPLOY_KEY_JSON=${CURRENT_DEPLOY_KEY_JSON/\%DEPLOY_KEY%/$(<${_DEPLOY_KEY_FILE})}
    CURRENT_DEPLOY_KEY_JSON=${CURRENT_DEPLOY_KEY_JSON/\%READ_ONLY%/${_READ_ONLY}}
    
    local _GITHUB_CREDS_FILE="${EL_CICD_TMP_PREFIX}.$(openssl rand -hex 5)"
    trap "rm -f ${EL_CICD_TMP_PREFIX}.*" EXIT
    echo "${CURRENT_DEPLOY_KEY_JSON}" > ${_GITHUB_CREDS_FILE}

    local _EL_CICD_GITHUB_KEYS_URL="https://${_GITHUB_API_HOST}/repos/${_GITHUB_ORG}/${_REPO_NAME}/keys"
    local _RESULT=$(${CURL_COMMAND} -X POST "${__GITHUB_HEADERS[@]}" -d @${_GITHUB_CREDS_FILE} ${_EL_CICD_GITHUB_KEYS_URL})

    echo
    _RESULT=$(echo ${_RESULT} | jq 'del(.key)')
    if [[ "$(echo ${_RESULT} | jq '.title // empty')" ]]
    then
        rm -f ${_GITHUB_CREDS_FILE}
        echo "ADDED NEW GITHUB DEPLOY KEY FOR ${_GITHUB_ORG}/${_REPO_NAME}:"
        echo ${_RESULT} | jq .
    else
        echo "ADDING NEW GITHUB DEPLOY KEY FOR ${_GITHUB_ORG}/${_REPO_NAME} FAILED:"
        echo ${_RESULT} | jq .
        cat ${_GITHUB_CREDS_FILE}
        exit 1
    fi
}

_delete_webhook() {
    local _GITHUB_HOST=${1}
    local _GITHUB_ORG=${2}
    local _REPO_NAME=${3}
    local _JENKINS_HOST=${4}
    local _PROJECT_ID=${5}
    local _MODULE_ID=${6}
    local _BUILD_TYPE=${7}
    local _WEB_TRIGGER_AUTH_TOKEN=${8}
    local _GIT_ACCESS_TOKEN=${9}

    __configure_github_headers ${_GIT_ACCESS_TOKEN}

    local _JENKINS_WEBOOK_URL="${_JENKINS_HOST}/job/${_PROJECT_ID}/job/${_MODULE_ID}-${_BUILD_TYPE}?token=${_WEB_TRIGGER_AUTH_TOKEN}"

    local _HOOKS_URL="https://${_GITHUB_HOST}/repos/${_GITHUB_ORG}/${_REPO_NAME}/hooks"

    local _HOOK_IDS=$(${CURL_COMMAND} "${__GITHUB_HEADERS[@]}" ${_HOOKS_URL} | jq ".[] | select(.config.url  == \"${_JENKINS_WEBOOK_URL}\") | .id" 2>/dev/null)

    echo
    if [[ ${_HOOK_IDS} ]]
    then
        for HOOK_ID in ${_HOOK_IDS}
        do
            ${CURL_COMMAND} -X DELETE "${__GITHUB_HEADERS[@]}" ${_HOOKS_URL}/${HOOK_ID}
            echo "--> DELETED GITHUB WEBHOOK ${HOOK_ID} FROM ${_GITHUB_ORG}/${_REPO_NAME}"
        done
    else
        echo "--> NO GITHUB WEBHOOKS FOUND FOR ${_GITHUB_ORG}/${_REPO_NAME}: SKIPPING"
    fi
}

_add_webhook() {
    local _GITHUB_HOST=${1}
    local _GITHUB_ORG=${2}
    local _REPO_NAME=${3}
    local _JENKINS_HOST=${4}
    local _PROJECT_ID=${5}
    local _MODULE_ID=${6}
    local _BUILD_TYPE=${7}
    local _WEB_TRIGGER_AUTH_TOKEN=${8}
    local _GIT_ACCESS_TOKEN=${9}

    __configure_github_headers ${_GIT_ACCESS_TOKEN}


    local _JENKINS_WEBOOK_URL="${_JENKINS_HOST}/job/${_PROJECT_ID}/job/${_MODULE_ID}-${_BUILD_TYPE}?token=${_WEB_TRIGGER_AUTH_TOKEN}"
    local _CURRENT_WEBHOOK_JSON=${GITHUB_WEBHOOK_JSON/\%JENKINS_WEBOOK_URL%/${_JENKINS_WEBOOK_URL}}

    local _WEBHOOK_FILE="${EL_CICD_TMP_PREFIX}.$(openssl rand -hex 5)"
    trap "rm -f ${EL_CICD_TMP_PREFIX}.*" EXIT
    echo ${_CURRENT_WEBHOOK_JSON} > ${_WEBHOOK_FILE}

    local _HOOKS_URL="https://${_GITHUB_HOST}/repos/${_GITHUB_ORG}/${_REPO_NAME}/hooks"
    local _WEBHOOK=$(${CURL_COMMAND} -X POST "${__GITHUB_HEADERS[@]}" -d @${_WEBHOOK_FILE} ${_HOOKS_URL} )

    echo
    echo "NEW GITHUB WEBHOOK CREATED:"
    echo ${_WEBHOOK} | jq '{"id":.id,"events": .events, "url": .config.url}'

    rm -f ${_WEBHOOK_FILE}
}
