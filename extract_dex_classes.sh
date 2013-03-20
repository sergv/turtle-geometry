#!/bin/bash
#
# File: extract_dex_classes.sh
#
# Created: Sunday, 17 March 2013
#

"$SDK_HOME/platform-tools/dexdump" -f ./bin/classes.dex | \
    awk "/^[ \t]*Class[ \t]*descriptor[ \t]*:[ \t]*'L[^;']*;'/" | \
    sed -r "s,^.*L([^;']*).*,\1," | \
    sort -u

exit 0

