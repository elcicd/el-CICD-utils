#!/usr/bin/env bash
# SPDX-License-Identifier: LGPL-2.1-or-later

_load_kubectl_msgs() {

EL_CICD_ADM_WARNING_MSG=$(cat <<-EOM
===================================================================

${_BOLD}WARNING:${_REGULAR} SUDO AND CLUSTER ADMIN PRIVILEGES REQUIRED WHEN USING THIS UTILITY

ACCESS TO el-CICD ONBOARDING AUTOMATION SERVERS SHOULD BE RESTRICTED TO CLUSTER ADMINS

===================================================================
EOM
)

EL_CICD_CLI_HELP_MSG=$(cat <<-EOM
Usage: oc elcicd-adm [OPTION]... [root-config-file]

el-CICD Admin Utility

Options:
    -A, --agents
        build the el-CICD Jenkins agent images

    -h, --help
        display this help text and exit

    -J, --jenkins
        build the el-CICD Jenkins image
        
    -L, --lab
        prepare a lab el-CICD cluster environment for working with, on, or demoing el-CICD
        
    -N, --non-prod
        bootstraps an el-CICD Master with non-prod onboarding pipelines
        
    -P, --prod
        bootstraps an el-CICD Master with prod onboarding pipelines
        
    --refresh-creds
        refresh el-CICD Master Server credentials

    --refresh-cicd-creds
        refresh all el-CICD Team Master Server credentials

    --sealed-secrets
        install/upgrade the Sealed Secret Helm Chart or version

    -S, --start-cluster
        start the OpenShift Local lab cluster

    -T, --tear-down-lab
        tear down a lab instance of el-CICD
        

root-config-file:
    file name or path to a root configuration file relative the root of the el-CICD-config directory
    
See the help documents for further informaton.
EOM
)

DEV_SETUP_WELCOME_MSG=$(cat <<-EOM
Welcome to the el-CICD lab environment setup utility.

The el-CICD lab setup utility will optionally perform one or more of the following:
 - Setup OpenShift Local, if downloaded to the el-CICD home directory and not currently installed
 - Setup up an image registry to mimic an external registry, with or without an NFS share
 - Clone and push all el-CICD and sample project Git repositories into your Git host (only GitHub is supported currently)
 - Install the Sealed Secrets controller onto your cluster

NOTES: Red Hat OpenShift Local may be downloaded from here:
       https://developers.redhat.com/products/openshift-local/overview
EOM
)

DEV_TEAR_DOWN_WELCOME_MSG=$(cat <<-EOM
Welcome to the el-CICD lab environment tear down utility.

Before beginning to tear down your environment, please make sure of the following:
 - Log into lab OKD cluster as cluster admin
 - Have root priveleges on this machine; sudo privileges are required

el-CICD will optionally tear down:
 - OpenShift Local
 - The cluster image registry
 - The NFS image registry directory
 - Remove el-CICD repositories pushed to your Git host
EOM
)

} # _load_kubectl_msgs

_echo_sudo_cluster_admin_warning() {
    echo
    echo "${EL_CICD_ADM_WARNING_MSG}"
}

_echo_loaded_scripts() {
    echo
    echo -n 'Loaded the following el-CICD scripts...'
    echo
    for FILE in $(echo ${EL_CICD_SCRIPTS} | xargs -n 1 basename)
    do
        echo "- ${FILE}"
    done
}

_execute_kubectl_el_cicd_adm() {
    echo
    echo ${ELCICD_ADM_MSG}
    sleep 3
    
    COMMANDS=("${@}")

    trap 'ERRO_LINENO=$LINENO' ERR
    trap '_failure' EXIT

    for COMMAND in ${COMMANDS[@]}
    do
        set -E
        eval ${COMMAND}
    done
}

_failure() {
   ERR_CODE=$?
   set +xv
   if [[  $- =~ e && ${ERR_CODE} != 0 ]]
   then
       echo
       echo "========= ${_BOLD}CATASTROPHIC COMMAND FAIL${_REGULAR} ========="
       echo
       echo "el-CICD EXITED ON ERROR CODE: ${ERR_CODE}"
       echo
       LEN=${#BASH_LINENO[@]}
       for (( INDEX=0; INDEX<$LEN-1; INDEX++ ))
       do
           echo '---'
           echo "FILE: $(basename ${BASH_SOURCE[${INDEX}+1]})"
           echo "  FUNCTION: ${FUNCNAME[${INDEX}+1]}"
           if [[ ${INDEX} > 0 ]]
           then
               echo "  COMMAND: ${FUNCNAME[${INDEX}]}"
               echo "  LINE: ${BASH_LINENO[${INDEX}]}"
           else
               echo "  COMMAND: ${BASH_COMMAND}"
               echo "  LINE: ${ERRO_LINENO}"
           fi
       done
       echo
       echo "======= END CATASTROPHIC COMMAND FAIL ======="
       echo
   fi
}

_unknown_cmd_opt_msg_and_exit() {
    echo
    echo "ERROR: Unknown command option '${1}'"
    echo
    echo "${EL_CICD_CLI_HELP_MSG}"
    exit 1
}

_confirm_logged_into_cluster() {
    if [[ -d ${HOME}/.crc ]]
    then
        echo
        echo "You must be logged into the cluster."
        echo "Confirming..."
        oc whoami > /dev/null 2>&1
        sleep 2
        echo "${_BOLD}Confirmed${_REGULAR}"
    fi
}

_confirm_continue() {
    if [[ "${ELCICD_ASSUME_YES}" != "${_TRUE}" ]]
    then
        read -t 0.1 -n 256 discard || :
        echo
        echo -n "Do you wish to continue? [${_YES}/${_NO}]: "
        CONTINUE='N'
        read CONTINUE
        if [[ ${CONTINUE} != ${_YES} ]]
        then
            echo
            echo "You must enter '${_BOLD}${_YES}${_REGULAR}' to continue.  Exiting..."
            exit 0
        fi
    fi
}

_get_yes_no_answer() {
    read -t 0.1 -n 256 discard || :
    read -p "${1}" -n 1 USER_ANSWER
    >&2 echo

    if [[ ${USER_ANSWER} == 'Y' ]]
    then
        echo ${_YES}
    else
        echo ${_NO}
    fi
}