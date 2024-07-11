#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later
_build_el_cicd_jenkins_image() {
    echo
    echo "Updating el-CICD Jenkins image ${JENKINS_IMAGE_NAME}"
    echo
    __init_jenkins_build

    set -ex
    podman build --squash \
        --build-arg OKD_VERSION=${OKD_VERSION} \
        --build-arg JENKINS_CONFIG_FILE_PATH=${JENKINS_CONFIG_FILE_PATH} \
        -t ${JENKINS_OCI_REGISTRY}/${JENKINS_IMAGE_NAME} \
        -f ${TARGET_JENKINS_BUILD_DIR}/Dockerfile.jenkins
        
    podman push --tls-verify=${JENKINS_OCI_REGISTRY_ENABLE_TLS} ${JENKINS_OCI_REGISTRY}/${JENKINS_IMAGE_NAME}
    set +ex

    rm -rf ${TARGET_JENKINS_BUILD_DIR}
    set +e
}

__init_jenkins_build() {
    rm -rf ${TARGET_JENKINS_BUILD_DIR}

    mkdir -p ${TARGET_JENKINS_BUILD_DIR}

    cp -vRT ${EL_CICD_CONFIG_JENKINS_DIR} ${TARGET_JENKINS_BUILD_DIR}

    if [[ "${JENKINS_AGENTS_BUILD_DIRS}" ]]
    then
        echo
        for BUILD_DIR in $(echo ${JENKINS_AGENTS_BUILD_DIRS} | tr ':' ' ')
        do
            cp -vRT ${BUILD_DIR} ${TARGET_JENKINS_BUILD_DIR}
        done
    fi
}

_build_el_cicd_jenkins_agent_images() {
    if [[ ${JENKINS_SKIP_AGENT_BUILDS} != ${_TRUE} ]]
    then
        echo
        echo "Creating Jenkins Agents"
        __init_jenkins_build

        BUILD_AGENT_NAMES=${BUILD_AGENT_NAMES:-"${JENKINS_AGENT_DEFAULT} $(echo ${JENKINS_BUILD_AGENT_NAMES} | tr ':' ' ')"}
        
        __verify_all_agent_dockerfiles_exist "${BUILD_AGENT_NAMES}"

        for AGENT_NAME in ${BUILD_AGENT_NAMES}
        do
            echo
            echo '==========================================='
            echo
            echo "STARTING JENKINS AGENT BUILD: ${AGENT_NAME}"
            echo
            echo '==========================================='
            echo

            set -e            
            podman build --squash \
                --build-arg OKD_VERSION=${OKD_VERSION} \
                --build-arg JENKINS_OCI_REGISTRY=${JENKINS_OCI_REGISTRY} \
                -t ${JENKINS_OCI_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${AGENT_NAME} \
                -f ${TARGET_JENKINS_BUILD_DIR}/Dockerfile.${AGENT_NAME}

            podman push --tls-verify=${JENKINS_OCI_REGISTRY_ENABLE_TLS} ${JENKINS_OCI_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${AGENT_NAME}
            set +e
        done

        rm -rf ${TARGET_JENKINS_BUILD_DIR}
    else
        echo
        echo "JENKINS_SKIP_AGENT_BUILDS is set to 'true'.  Jenkins agent builds skipped."
    fi
}

__verify_all_agent_dockerfiles_exist() {
    BUILD_AGENT_NAMES=${1}
    
    for AGENT_NAME in ${BUILD_AGENT_NAMES}
    do
        if [[ ! -f ${TARGET_JENKINS_BUILD_DIR}/Dockerfile.${AGENT_NAME} ]]
        then
            echo
            echo "ERROR: Dockerfile FOR AGENT ${AGENT_NAME} DOES NOT EXIST [${EL_CICD_CONFIG_JENKINS_DIR}/Dockerfile.${AGENT_NAME}]"
            echo
            exit 1
        fi
    done
}

_base_jenkins_agent_exists() {
    _IMAGE_URL=docker://${JENKINS_OCI_REGISTRY}/${JENKINS_AGENT_IMAGE_PREFIX}-${JENKINS_AGENT_DEFAULT}
    local _HAS_BASE_AGENT=$(skopeo inspect --format '{{.Name}}({{.Digest}})' --tls-verify=${JENKINS_OCI_REGISTRY_ENABLE_TLS} ${_IMAGE_URL} 2> /dev/null)
    if [[ -z ${_HAS_BASE_AGENT} ]]
    then
        echo ${_FALSE}
    else
        echo ${_TRUE}
    fi
}

get_jenkins_image_sha() {
    local _IMAGE_URL=docker://${JENKINS_OCI_REGISTRY}/${JENKINS_IMAGE_NAME}
    JENKINS_MASTER_IMAGE_SHA=$(skopeo inspect --format '{{.Digest}}' --tls-verify=${JENKINS_OCI_REGISTRY_ENABLE_TLS} ${_IMAGE_URL} 2> /dev/null)
    if  ! command -v skopeo &> /dev/null
    then
        echo
        echo "ERROR: ${_BOLD}skopeo MUST BE INSTALLED${_REGULAR} to run el-CICD; Exiting..."
        exit 1
    fi
}

