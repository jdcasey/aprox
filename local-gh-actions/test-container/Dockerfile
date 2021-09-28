FROM quay.io/fedora/fedora-minimal:34

ENV INDY_ETC_DIR /usr/share/indy/etc

EXPOSE 8080 8081 8000

ADD start-indy.py /usr/local/bin/start-indy.py

ADD launcher/target/indy-launcher-*-skinny.tar.gz /tmp/indy-launcher.tar.gz
RUN	tar -xf /tmp/indy-launcher.tar.gz -C /opt

ADD launcher/target/indy-launcher-*-data.tar.gz /tmp/indy-launcher-data.tar.gz

RUN	mkdir -p /usr/share/indy /home/indy && \
	tar -xf /tmp/indy-launcher-data.tar.gz -C /usr/share/indy

RUN chmod +x /usr/local/bin/*

ENTRYPOINT ["/usr/local/bin/dumb-init", "/usr/local/bin/start-indy.py"]

RUN mkdir -p /etc/indy && mkdir -p /var/log/indy && mkdir -p /usr/share/indy /opt/indy/var/log/indy

RUN chmod -R 777 /etc/indy && chmod -R 777 /var/log/indy && chmod -R 777 /usr/share/indy

RUN yum install -y java-11-openjdk-devel.x86_64 gettext unzip

RUN cp -rf /opt/indy/var/lib/indy/ui /usr/share/indy/ui

RUN yum clean all && rm -rf /var/cache/yum /tmp/yum.log

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

ENV LOGNAME=indy
ENV HOME=/home/indy
