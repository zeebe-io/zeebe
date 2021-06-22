#!/bin/bash
set -exo pipefail

# The purpose of this script is to make it easier for developers to setup new benchmarks.
# As input the benchmark name is expected, which is used for the docker image tag and k8 namespace.
# This script does the following:
#
# 1. Build the current branch
# 2. Build the docker dev image
# 3. Publish the docker image
# 4. Setups new namespace
# 5. Configures the benchmark
# 6. Deploy's the benchmark

# Contains OS specific sed function
source benchmarks/setup/utils.sh

if [ -z $1 ]
then
  echo "Please provide a benchmark name! Format should be 'YOUR_NAME-TOPIC'"
  exit 1
fi
benchmark=$1

if [ -z $2 ]
then
  echo "Please provide a image version"
  exit 1
fi
version=$2

# Check if docker daemon is running
if ! docker info >/dev/null 2>&1; then
    echo "Docker daemon does not seem to be running, make sure it's running and retry"
    exit 1
fi

cd benchmarks/project

sed_inplace "s/:SNAPSHOT/:$benchmark/" docker-compose.yml
# Use --no-cache to force rebuild the image for the benchmark application. Without this changes to zeebe-client were not picked up. This can take longer to build.
docker-compose build
docker-compose push
git restore -- docker-compose.yml

cd ../setup/

./newBenchmark.sh "$benchmark"

cd "$benchmark"

# calls OS specific sed inplace function
sed_inplace "s/SNAPSHOT/$version/" zeebe-values.yaml
sed_inplace "s/starter:SNAPSHOT/starter:$benchmark/" starter.yaml
sed_inplace "s/starter:SNAPSHOT/starter:$benchmark/" simpleStarter.yaml
sed_inplace "s/starter:SNAPSHOT/starter:$benchmark/" timer.yaml
sed_inplace "s/worker:SNAPSHOT/worker:$benchmark/" worker.yaml

make zeebe starter worker
