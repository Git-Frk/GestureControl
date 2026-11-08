#!/usr/bin/env bash
# Source this file from any directory after the project-local setup in README.md.
GESTUREOS_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
export JAVA_HOME="$GESTUREOS_ROOT/.tools/env/lib/jvm"
export ANDROID_HOME="$GESTUREOS_ROOT/.tools/android-sdk"
export ANDROID_USER_HOME="$GESTUREOS_ROOT/.tools/android-user"
export GRADLE_USER_HOME="$GESTUREOS_ROOT/.tools/gradle-user"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/19.0/bin:$ANDROID_HOME/platform-tools:$PATH"
