#!/bin/sh
set -eu

BASE_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)

if [ -x "${JAVA_HOME:-}/bin/java" ]; then
  JAVA_CMD="$JAVA_HOME/bin/java"
elif [ -x "$BASE_DIR/.tools/jdk/bin/java" ]; then
  JAVA_HOME="$BASE_DIR/.tools/jdk"
  export JAVA_HOME
  JAVA_CMD="$JAVA_HOME/bin/java"
else
  echo "Java 25 was not found. Run ./scripts/bootstrap-toolchain.sh first." >&2
  exit 1
fi

WRAPPER_JAR="$BASE_DIR/.mvn/wrapper/maven-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
  echo "Maven Wrapper is not installed. Run ./scripts/bootstrap-toolchain.sh first." >&2
  exit 1
fi

exec "$JAVA_CMD" \
  -classpath "$WRAPPER_JAR" \
  "-Dmaven.multiModuleProjectDirectory=$BASE_DIR" \
  org.apache.maven.wrapper.MavenWrapperMain "$@"

