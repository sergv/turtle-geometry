#!/bin/bash
#
# File: extract_dex_classes.sh
#
# Created: Sunday, 17 March 2013
#

target="$1"

if [ -z "${target}" ]; then
    target="./bin/classes.dex"
fi

"$SDK_HOME/platform-tools/dexdump" -f "${target}" | \
    awk "/^[ \t]*Class[ \t]*descriptor[ \t]*:[ \t]*'L[^;']*;'/" | \
    sed -r "s,^.*L([^;']*).*,\1," | \
    sort -u

exit 0

