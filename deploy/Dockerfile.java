FROM python:3.12-slim AS java

ARG JDK_URL="https://github.com/adoptium/temurin25-binaries/releases/download/jdk-25.0.3%2B9/OpenJDK25U-jdk_x64_linux_hotspot_25.0.3_9.tar.gz"
ARG JDK_SHA256="69264a7a211bf5029830d07bc3370f879769d62ebc5b5488e90c9343a2da0e1f"

RUN python -c "import hashlib,pathlib,tarfile,urllib.request; \
p=pathlib.Path('/tmp/jdk.tar.gz'); \
urllib.request.urlretrieve('${JDK_URL}', p); \
assert hashlib.sha256(p.read_bytes()).hexdigest() == '${JDK_SHA256}'; \
target=pathlib.Path('/opt/java'); \
target.mkdir(parents=True); \
tarfile.open(p, 'r:gz').extractall(target, filter='data'); \
roots=list(target.iterdir()); \
assert len(roots) == 1 and roots[0].is_dir(); \
[child.rename(target / child.name) for child in roots[0].iterdir()]; \
roots[0].rmdir(); \
p.unlink()" \
    && chmod -R a+rX /opt/java

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

FROM debian:12.12-slim

ARG APP_NAME
ARG APP_JAR
ENV RAG_APP_NAME=${APP_NAME}

RUN apt-get update \
    && apt-get install --yes --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/* \
    && test -n "${APP_NAME}" \
    && test -n "${APP_JAR}" \
    && groupadd --system rag \
    && useradd --system --gid rag --create-home --home-dir /home/rag rag

ENV JAVA_HOME=/opt/java
ENV PATH="${JAVA_HOME}/bin:${PATH}"

WORKDIR /app

COPY --from=java /opt/java /opt/java
COPY --chown=rag:rag ${APP_JAR} /app/application.jar

USER rag

HEALTHCHECK --interval=15s --timeout=5s --start-period=30s --retries=5 \
    CMD if [ "${RAG_APP_NAME}" = "rag-worker" ]; then kill -0 1; else curl --fail --silent http://127.0.0.1:8080/actuator/health/readiness; fi

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/application.jar"]
