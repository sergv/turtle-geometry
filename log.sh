#!/bin/bash
#
# File: log.sh
#
# Created: Wednesday,  6 March 2013
#

device_arg=""
if adb devices 2>&1 | grep -E '^[0-9a-zA-Z]+.device$' >/dev/null; then
    device_arg="-d"
elif adb devices 2>&1 | grep emulator >/dev/null; then
    device_arg="-e"
else
    echo "error: no devices or emulators found" >&2
    exit 1
fi

adb "${device_arg}" logcat | \
    awk '/AndroidRuntime|TurtleGeometry|IndependentDrawer|LineRenderer|Neko|GLSurfaceView|GLThread|libEGL|OpenGLRenderer|[Dd]alvik|stdout|stderr|System\.out/ && !/Multiwindow|MultiWindowManagerService/ { sub(/^I\/System\.out\([0-9 ]+\):/, ""); print }'

exit 0

