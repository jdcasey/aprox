#FROM maven:3.8-adoptopenjdk-11 AS MAVEN_BUILD
#
#ARG INDY_VERSION
#RUN echo "$INDY_VERSION"
#
#COPY . /tmp/indy
#WORKDIR /tmp/indy
#
#RUN find "${PWD}" -maxdepth 1
#
#RUN mvn -B -e -Dmaven.artifact.threads=20 -T 4 -DskipTests=true -s local-gh-actions/test-container/settings-gh.xml package
#


FROM quay.io/fedora/fedora:34

EXPOSE 8080 8081

RUN dnf update -y && \
    dnf install -y java-11-openjdk-devel.x86_64 unzip tar gzip findutils python3

ARG INDY_VERSION

RUN echo "$INDY_VERSION"

ADD deployments/launcher/target/indy-launcher-$INDY_VERSION-skinny.tar.gz /opt
ADD deployments/launcher/target/indy-launcher-$INDY_VERSION-data.tar.gz /opt/indy/var/lib/indy

#COPY --from=MAVEN_BUILD /tmp/indy/deployments/launcher/target/indy-launcher-$INDY_VERSION-skinny.tar.gz /tmp/indy-launcher.tar.gz
#COPY --from=MAVEN_BUILD /tmp/indy/deployments/launcher/target/indy-launcher-$INDY_VERSION-data.tar.gz /tmp/indy-data.tar.gz
#
#RUN tar -zxvf /tmp/indy-launcher.tar.gz -C /opt
#RUN tar -zxvf /tmp/indy-data.tar.gz -C /opt/indy/var/lib/indy

RUN mkdir -p /usr/share/indy /opt/indy/var/log/indy /opt/indy/var/lib/indy /opt/indy/etc

ADD local-gh-actions/test-container/etc /opt/indy/etc/indy
ADD local-gh-actions/test-container/dumb-init /usr/local/bin/dumb-init

RUN chmod 0775 /usr/local/bin/*

RUN ls -alh /usr/local/bin

ENTRYPOINT ["/usr/local/bin/dumb-init", "/opt/indy/bin/indy.sh"]
#ENTRYPOINT ["/bin/bash"]
