#!/bin/bash
#
# File: log.sh
#
# Created: Wednesday,  6 March 2013
#

adb logcat -c
adb logcat | awk '/AndroidRuntime|TurtleGeometry/ && !/Multiwindow|MultiWindowManagerService/'

exit 0

