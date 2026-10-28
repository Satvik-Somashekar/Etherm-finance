# Runtime image for one backend service. Build the jars first with `mvn clean install`;
# docker-compose.yml builds this once per module by passing MODULE.
# The base image is pinned by digest so a rebuild cannot silently pick up a different image; Dependabot
# proposes updates. The tag is kept for readers.
FROM eclipse-temurin:21-jre@sha256:f37956012e0cb88400e5b3689aeae2d2d735364c1922ae2225536163b5a99f56
ARG MODULE
WORKDIR /app
# Run as an unprivileged user with no shell or home, never as root.
RUN groupadd --system etherm && useradd --system --gid etherm --no-create-home --shell /usr/sbin/nologin etherm
COPY --chown=root:root --chmod=0444 ${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar app.jar
USER etherm
# A read-only root filesystem needs a writable /tmp, which docker-compose mounts as tmpfs.
ENTRYPOINT ["java", "-XX:+ExitOnOutOfMemoryError", "-Djava.io.tmpdir=/tmp", "-jar", "/app/app.jar"]
