#!/usr/bin/env bash
set -ex

cd $(dirname "${0}")

TEAM_ID=${1}

PROJECT_ID=${2}
PROJECT_YAML=${PROJECT_ID}-helmOut.yaml

TMP_DIR=./${TEAM_ID}-tmp

rm -rf ${TMP_DIR}
mkdir ${TMP_DIR}

cat <&0 > ${TMP_DIR}/${PROJECT_YAML}

helm template --set-string elCicdDefs.PROJECT_ID=${PROJECT_ID},elCicdDefs.TEAM_ID=${TEAM_ID} \
    -f onboarding-kustomization.yaml project-labels \
    ${elcicd_EL_CICD_HELM_OCI_REGISTRY}/elcicd-chart | sed -E '/^#|^---/d' > ${TMP_DIR}/kustomization.yaml
    
kustomize build ${TMP_DIR}