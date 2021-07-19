#!/bin/bash -eux

chmod +x clients/go/cmd/zbctl/dist/zbctl

zbctl="clients/go/cmd/zbctl/dist/zbctl"

"${zbctl}" create instance external-tool-integration --variables "${QA_RUN_VARIABLES}"

businessKey="${BUSINESS_KEY}"

echo "Waiting for result of $businessKey"
jobKey=""

while true; do
    # activate jobs non-zero exits can be ignored with `<command> || true`
    "${zbctl}" activate jobs "$businessKey" > activationresponse.txt 2>/dev/null || true
    jobKey=$(jq -r '.jobs[0].key' < activationresponse.txt)

    if [[ -z "$jobKey" || "$jobKey" == "null" ]]; then
        echo "Still waiting"
        sleep 5m
    else
        echo "QA run completed"
        break
    fi
done

echo "Job key is: $jobKey"
variables=$(jq -r '.jobs[0].variables' < activationresponse.txt)
echo "Job variables are: $variables"

testResult=$(echo "$variables" | jq -r '.aggregatedTestResult')
echo "Test result is: $testResult"

"${zbctl}" complete job "$jobKey"

if [ "$testResult" == "FAILED" ]; then
  echo "Test failed"
  exit 1
else
  echo "Test passed or skipped"
  exit 0
fi
