#FROM maven:3.8-adoptopenjdk-11 AS MAVEN_BUILD
#COPY . /tmp/indy
#WORKDIR /tmp/indy
#RUN sysctl -w fs.file-max=1000000
#RUN mvn package


FROM debian:latest

ARG INDY_VERSION
ENV INDY_ETC_DIR /usr/share/indy/etc
ENV LOGNAME=indy
ENV HOME=/home/indy

EXPOSE 8080 8081 8000

RUN mkdir -p /etc/indy && mkdir -p /var/log/indy && mkdir -p /usr/share/indy /opt/indy/var/log/indy

RUN chmod -R 777 /etc/indy && chmod -R 777 /var/log/indy && chmod -R 777 /usr/share/indy

RUN apt-get update
RUN apt-get upgrade -y
RUN apt-get install -y openjdk-11-jdk-headless unzip tar gzip

RUN	mkdir -p /usr/share/indy /home/indy

ADD local-gh-actions/test-container/start-indy.py /usr/local/bin/start-indy.py

ADD dist/indy-launcher-$INDY_VERSION-skinny.tar.gz /opt
ADD dist/indy-launcher-$INDY_VERSION-data.tar.gz /usr/share/indy

#ADD --from=MAVEN-BUILD /tmp/indy/deployment/launcher/indy-launcher-$INDY_VERSION-skinny.tar.gz /opt
#ADD --from=MAVEN_BUILD /tmp/indy/deployment/launcher/indy-launcher-$INDY_VERSION-data.tar.gz /usr/share/indy

RUN chmod +x /usr/local/bin/*
RUN cp -rf /opt/indy/var/lib/indy/ui /usr/share/indy/ui

# Run as non-root user
#RUN chgrp -R 0 /opt
#RUN chmod -R g=u /opt
#RUN chgrp -R 0 /usr/share/indy
#RUN chmod -R g=u /usr/share/indy


ENTRYPOINT ["/bin/bash", "/usr/local/bin/start-indy.py"]