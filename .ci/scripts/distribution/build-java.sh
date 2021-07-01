#!/bin/sh -eux

# getconf is a POSIX way to get the number of processors available which works on both Linux and macOS
LIMITS_CPU=${LIMITS_CPU:-$(getconf _NPROCESSORS_ONLN)}
MAVEN_PARALLELISM=${MAVEN_PARALLELISM:-$LIMITS_CPU}

mvn -B -T${MAVEN_PARALLELISM} -s ${MAVEN_SETTINGS_XML} -DskipTests -DskipChecks clean install -Pchecks,spotbugs,prepare-offline,extract-flaky-tests
