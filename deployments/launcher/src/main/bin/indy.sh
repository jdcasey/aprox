#!/bin/bash
#
# Copyright (C) 2011-2020 Red Hat, Inc. (https://github.com/Commonjava/indy)
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#         http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#


test -f /etc/profile && source /etc/profile
test -f $HOME/.bash_profile &&source $HOME/.bash_profile

THIS=$(cd ${0%/*} && echo $PWD/${0##*/})
# THIS=`realpath ${0}`
BASEDIR=`dirname ${THIS}`
BASEDIR=`dirname ${BASEDIR}`

# echo "basedir: ${BASEDIR}"

INDY_LOCALLIB_DIR=${INDY_LOCALLIB_DIR:-${BASEDIR}/lib/local}
INDY_LOGCONF_DIR=${INDY_LOGCONF_DIR:-${BASEDIR}/etc/indy/logging}

echo "Loading logging config from: ${INDY_LOGCONF_DIR}"

CP="${INDY_LOCALLIB_DIR}:${INDY_LOGCONF_DIR}"
for f in $(find $BASEDIR/lib/indy*-embedder-*.jar -type f)
do
  CP=${CP}:${f}
done

# To avoid jboss logging import from weld with old version.
jb_logging=$(find $BASEDIR/lib/thirdparty/jboss-logging-*.jar -type f)
if [ -f $jb_logging ]; then
  CP=${CP}:$jb_logging
fi

for f in $(find $BASEDIR/lib/thirdparty -type f)
do
  if [[ $f != *jboss-logging* ]]; then
    CP=${CP}:${f}
  fi
done

# echo "Classpath: ${CP}"

JAVA=`which java`
$JAVA -version 2>&1 > /dev/null
if [ $? != 0 ]; then
  PATH=${JAVA_HOME}/bin:${PATH}
  JAVA=${JAVA_HOME}/bin/java
fi


INDY_ENV=${INDY_ENV:-${BASEDIR}/etc/indy/env.sh}
test -f ${INDY_ENV} && source ${INDY_ENV}

#JAVA_DEBUG_OPTS="-Xrunjdwp:transport=dt_socket,address=8000,server=y,suspend=n"
JAVA_OPTS="$JAVA_OPTS $JAVA_DEBUG_OPTS"

MAIN_CLASS=org.commonjava.indy.boot.jaxrs.JaxRsBooter

exec "$JAVA" ${JAVA_OPTS} -cp "${CP}" -Dindy.home="${BASEDIR}" -Dindy.boot.defaults=${BASEDIR}/bin/boot.properties -Dorg.jboss.logging.provider=slf4j -Dhttps.protocols=TLSv1,TLSv1.1,TLSv1.2 -Djava.net.preferIPv4Stack=true ${MAIN_CLASS} "$@"
