#!/bin/sh
# @version $Revision$ ($Author$) $Date$
#
SCRIPTS_DIR=`dirname ${0}`/..
. ${SCRIPTS_DIR}/environment.sh

java -cp ${BLUECOVE_3RDPARTY_HOME}/avetanaBluetooth/avetanaBluetooth.jar:${BLUECOVE_TESTER_JAR} ${BLUECOVE_MAIN} $*
rc=$?
if [ ! "${rc}" = "0" ]; then
    echo Error calling java
    echo Prsess enter to exit
    read
fi

