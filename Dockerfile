ARG APP_ENV=prod
ARG BASE=distball

# Maven builder when no tarball is provided
# This stage uses a local Maven .m2 repository; if you want to speed it up, build it locally as well
# using it, and it will be included in the build context. Or simply do a simply link to your normal
# repository.
FROM maven:3.8.1-openjdk-11-slim as maven
COPY ./ ./
RUN mvn -T1C -DskipChecks -Dmaven.test.skip package
RUN cp dist/target/camunda-cloud-zeebe-*.tar.gz /tmp/zeebe.tar.gz

# Distball image builder
FROM alpine:latest as distball
ARG DISTBALL

COPY ${DISTBALL} /tmp/zeebe.tar.gz

# Unpack tar ball
FROM ${BASE} as builder

ENV TMP_DIR=/tmp/zeebe \
    TINI_VERSION=v0.19.0

RUN mkdir -p ${TMP_DIR} && \
    tar xfvz /tmp/zeebe.tar.gz --strip 1 -C ${TMP_DIR} && \
    # already create volume dir to later have correct rights
    mkdir ${TMP_DIR}/data

ADD https://github.com/krallin/tini/releases/download/${TINI_VERSION}/tini ${TMP_DIR}/bin/tini
COPY docker/utils/startup.sh ${TMP_DIR}/bin/startup.sh
RUN chmod +x -R ${TMP_DIR}/bin/
RUN chmod 0775 ${TMP_DIR} ${TMP_DIR}/data

# Building prod image
FROM openjdk:11-jre-slim as prod
RUN echo "running PROD pre-install commands"

# Building dev image
FROM openjdk:11 as dev
RUN echo "running DEV pre-install commands"
RUN apt-get update
RUN wget -O - https://github.com/jvm-profiling-tools/async-profiler/releases/download/v1.7.1/async-profiler-1.7.1-linux-x64.tar.gz | tar xzv

# Building application image
FROM ${APP_ENV} as app

ENV ZB_HOME=/usr/local/zeebe \
    ZEEBE_BROKER_GATEWAY_NETWORK_HOST=0.0.0.0 \
    ZEEBE_STANDALONE_GATEWAY=false
ENV PATH "${ZB_HOME}/bin:${PATH}"

WORKDIR ${ZB_HOME}
EXPOSE 26500 26501 26502
VOLUME ${ZB_HOME}/data

RUN groupadd -g 1000 zeebe && \
    adduser -u 1000 zeebe --system --ingroup zeebe && \
    chmod g=u /etc/passwd && \
    chown 1000:0 ${ZB_HOME} && \
    chmod 0775 ${ZB_HOME}

COPY --from=builder --chown=1000:0 /tmp/zeebe/bin/startup.sh /usr/local/bin/startup.sh
COPY --from=builder --chown=1000:0 /tmp/zeebe ${ZB_HOME}

ENTRYPOINT ["tini", "--", "/usr/local/bin/startup.sh"]
