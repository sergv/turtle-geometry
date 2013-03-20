#!/bin/bash
#
# File: log.sh
#
# Created: Wednesday,  6 March 2013
#

if [ "$1" != "no-clear" ]; then
    adb logcat -c
fi
adb logcat | \
    awk '/AndroidRuntime|TurtleGeometry|stdout|stderr|System\.out/ && !/Multiwindow|MultiWindowManagerService/ { sub(/^I\/System\.out\([0-9 ]+\):/, ""); print }'

exit 0

