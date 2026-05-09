#!/bin/bash

[[ "$@" =~ "--kubelint" ]] && doKubeLint=true
[[ "$@" =~ "--kubeconform" ]] && doKubconform=true

workspaceDir=$(pwd)
exitCode=0

function lintChart() {(
  set -e
  chartDir=$(dirname $1)
  echo "[INFO] Linting Helm chart @ $chartDir ..."
  cd $chartDir

  # select additional values file for linting
  if [ -f "values-lint.yaml" ]; then
    valueFiles="-f values-lint.yaml"
  elif [ -f "values-dev.yaml" ]; then
    valueFiles="-f values-dev.yaml"
  else
    valueFiles=""
  fi

  # lint helm chart
  helm dependency update .
  helm lint . $valueFiles -n helm-lint

  # render chart templates ...
  helm template . $valueFiles -n helm-lint > .helm-template-output.yaml

  # ... and lint the yaml output
  yamllint -c $workspaceDir/.yamllint.yaml .helm-template-output.yaml

  # run kube-linter if enabled (not on clusters/xxx)
  if [[ $doKubeLint ]] && [[ ! $chartDir =~ "clusters/" ]]; then
    if [ -f ".kube-linter.yaml" ]; then
      kubeLintConfig=".kube-linter.yaml"
    else
      kubeLintConfig="$workspaceDir/.kube-linter.yaml"
    fi
    echo "[DEBUG] kube-linter lint --config $kubeLintConfig @ $chartDir"
    kube-linter lint --config $kubeLintConfig .helm-template-output.yaml
  fi

  # run kubeconform if enabled (not on clusters/xxx)
  if [[ $doKubconform ]] && [[ ! $chartDir =~ "clusters/" ]]; then
    echo "[DEBUG] kubeconform @ $chartDir"
    kubeconform -ignore-missing-schemas -summary .helm-template-output.yaml
  fi

  # cleanup
  rm .helm-template-output.yaml
)}


#### main

# single chart path given
if [[ "${@:-1}" =~ "Chart.yaml" ]]; then
  lintChart ${@:-1}
  exit $?
fi

# lint all Helm charts found in workspace
for chartFile in $(find . -name Chart.yaml); do
  lintChart $chartFile
  lintStatus=$?
  if [[ $lintStatus > 0 ]]; then
    echo "[ERROR] Lint failed for $chartFile"
  fi
  ((exitCode+=$lintStatus))
  cd $workspaceDir
done

exit $exitCode