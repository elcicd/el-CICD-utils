#!/usr/bin/bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_load_kubectl_msgs() {

WARNING_MSG=$(cat <<-EOM
===================================================================

${_BOLD}WARNING:${_REGULAR} SUDO AND CLUSTER ADMIN PRIVILEGES REQUIRED WHEN USING THIS UTILITY

ACCESS TO el-CICD ONBOARDING AUTOMATION SERVERS SHOULD BE RESTRICTED TO CLUSTER ADMINS

===================================================================
EOM
)

HELP_MSG=$(cat <<-EOM
Usage: oc el-cicd-adm [OPTION] [root-config-file]

el-CICD Admin Utility

Options:
    -b,   --bootstrap:         bootstraps the el-CICD Onboarding Server
          --config:            generate el-CICD configuration files, both bootstrap and runtime
    -l,   --lab                flags the bootstrap process to use demo/test configuration values
    -L,   --setup-lab:         set a lab instance of el-CICD for dev,demo, or testing purposes
    -T,   --tear-down-dev:     tear down an environment for developing el-CICD
    -S    --start-crc:         start OpenShift Local development cluster
    -c,   --onboarding-creds:  refresh an el-CICD Onboarding Server credentials
    -C,   --cicd-creds:        run the refresh-credentials pipeline on the el-CICD onboarding server
    -s,   --sealed-secrets:    reinstall/upgrade Sealed Secrets
    -j,   --jenkins:           build el-CICD Jenkins image
    -a,   --agents:            build el-CICD Jenkins agent images
    -h,   --help:              display this help text and exit

root-config-file:
    file name or path to a root configuration file relative the root of the el-CICD-config directory
EOM
)

DEV_SETUP_WELCOME_MSG=$(cat <<-EOM
Welcome to the el-CICD setup utility for developing with or on el-CICD.

el-CICD will perform the necessary setup for running the tutorial or developing with el-CICD:
  - Optionally setup OpenShift Local, if downloaded to the el-CICD home directory and not currently installed.
  - Optionally setup up an image registry to mimic an external registry, with or without an NFS share.
  - Clone and push all el-CICD and sample project Git repositories into your Git host (only GitHub is supported currently).
  - Install the Sealed Secrets controller onto your cluster

NOTES: Red Hat OpenShift Local may be downloaded from here:
       https://developers.redhat.com/products/openshift-local/overview
EOM
)

DEV_TEAR_DOWN_WELCOME_MSG=$(cat <<-EOM
Welcome to the el-CICD dev environment tear down utility.
Before beginning to tear down your environment, please make sure of the following:

1) Log into an OKD cluster as cluster admin, or you can use Red Hat OpenShift Local:
  https://developers.redhat.com/products/openshift-local/overview
  NOTE: el-CICD can setup OpenShift Local for you if requested.

2) Have root priveleges on this machine. sudo password is required to complete setup.

el-CICD will optionally tear down:
    - OpenShift Local
    - The cluster image registry
    - The NFS registry directory
    - Remove el-CICD repositories pushed to your Git host
EOM
)

}

_execute_kubectl_el_cicd_adm() {
    set -E

    trap 'ERRO_LINENO=$LINENO' ERR
    trap '_failure' EXIT

    echo
    echo 'el-CICD environment loaded'

    echo
    echo "${WARNING_MSG}"
    sleep 2
    
    echo
    echo ${ELCICD_ADM_MSG}

    set +o allexport

    if [[ ! -z ${EL_CICD_LAB_INSTALL} ]]
    then
        if [[ ! -z $(command -v crc) ]]
        then
            CRC_EXEC=$(which crc)
        elif [[ -z $(which oc --skip-alias 2>/dev/null) ]]
        then
            echo
            echo '============ WARNING!!! =============='
            echo
            echo 'IF NOT INSTALLING OPENSHIFT LOCAL, YOU MUST INSTALL THE OKD CLI!'
            echo
            echo '============ WARNING!!! =============='
        fi
    fi

    if [[ ! -z ${BOOTSTRAP} ]]
    then
        _verify_scm_secret_files_exist

        _verify_pull_secret_files_exist
    fi

    for COMMAND in ${EL_CICD_ADM_COMMANDS[@]}
    do
        eval ${COMMAND}
    done
}