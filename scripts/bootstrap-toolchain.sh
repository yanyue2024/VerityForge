#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOOLS_DIR="${ROOT_DIR}/.tools"
JDK_DIR="${TOOLS_DIR}/jdk"
JDK_ARCHIVE="${TOOLS_DIR}/temurin-25.tar.gz"
JDK_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz"
JDK_SHA256="69264a7a211bf5029830d07bc3370f879769d62ebc5b5488e90c9343a2da0e1f"
WRAPPER_JAR="${ROOT_DIR}/.mvn/wrapper/maven-wrapper.jar"
WRAPPER_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.4/maven-wrapper-3.3.4.jar"

mkdir -p "${TOOLS_DIR}" "$(dirname "${WRAPPER_JAR}")"

if [ ! -x "${JDK_DIR}/bin/java" ]; then
  echo "Downloading Temurin JDK 25..."
  curl -fL -C - "${JDK_URL}" -o "${JDK_ARCHIVE}"
  echo "${JDK_SHA256}  ${JDK_ARCHIVE}" | sha256sum -c -
  rm -rf "${JDK_DIR}"
  mkdir -p "${JDK_DIR}"
  tar -xzf "${JDK_ARCHIVE}" --strip-components=1 -C "${JDK_DIR}"
  rm -f "${JDK_ARCHIVE}"
fi

if [ ! -f "${WRAPPER_JAR}" ]; then
  echo "Downloading Maven Wrapper..."
  curl -fL "${WRAPPER_URL}" -o "${WRAPPER_JAR}"
fi

echo "Toolchain ready:"
"${JDK_DIR}/bin/java" -version
"${ROOT_DIR}/mvnw" -version
