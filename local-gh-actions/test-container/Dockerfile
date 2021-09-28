FROM debian:latest

ENV INDY_ETC_DIR /usr/share/indy/etc
ENV LOGNAME=indy
ENV HOME=/home/indy

EXPOSE 8080 8081 8000

RUN mkdir -p /etc/indy && mkdir -p /var/log/indy && mkdir -p /usr/share/indy /opt/indy/var/log/indy

RUN chmod -R 777 /etc/indy && chmod -R 777 /var/log/indy && chmod -R 777 /usr/share/indy

RUN apt-get update
RUN apt-get upgrade -y
RUN apt-get install -y openjdk-11-jdk-headless unzip tar gzip

ADD start-indy.py /usr/local/bin/start-indy.py

ADD indy/indy-launcher-$INDY_VERSION-skinny.tar.gz /tmp/indy-launcher.tar.gz
RUN	tar -xf /tmp/indy-launcher.tar.gz -C /opt

ADD indy/indy-launcher-$INDY_VERSION-data.tar.gz /tmp/indy-launcher-data.tar.gz

RUN	mkdir -p /usr/share/indy /home/indy && \
	tar -xf /tmp/indy-launcher-data.tar.gz -C /usr/share/indy

RUN chmod +x /usr/local/bin/*

RUN cp -rf /opt/indy/var/lib/indy/ui /usr/share/indy/ui

# Run as non-root user
RUN chgrp -R 0 /opt && \
    chmod -R g=u /opt && \
    chgrp -R 0 /etc/indy && \
    chmod -R g=u /etc/indy && \
    chgrp -R 0 /var/log/indy && \
    chmod -R g=u /var/log/indy && \
    chgrp -R 0 /usr/share/indy && \
    chmod -R g=u /usr/share/indy && \
    chgrp -R 0 /home/indy && \
    chmod -R g=u /home/indy && \
    chown -R 1001:0 /home/indy && \
    chmod 644 /etc/profile.d/setup-user.sh


ENTRYPOINT ["/bin/bash", "/usr/local/bin/start-indy.py"]