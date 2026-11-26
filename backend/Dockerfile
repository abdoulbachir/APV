# syntax=docker/dockerfile:1
#
# The backend as a container image, for the private test site (deploy/test/README.md).
#
# No Spring profile is set, so every production rule applies. The image holds the application
# and nothing else: no breached-password list, no key, no fixture, no development data and no
# secret. All of those come from the environment and from a read-only mount at run time.
#
#   docker build -t apv-backend backend/
#
# Both base images are pinned by version and by digest. To update: change the tag, then take
# the digest that "docker buildx imagetools inspect eclipse-temurin:<tag>" prints.

# ------------------------------------------------------------------------------------------
# Build: the jar, with the Maven wrapper on Java 21. Tests are not run and not compiled here:
# CI runs them, and their sources are not even sent to the build (.dockerignore).
# ------------------------------------------------------------------------------------------
FROM eclipse-temurin:21.0.13.1_2-jdk-noble@sha256:5f9489c60984e9715afba8e7da353e9d78d2a38c0517662f5cc29fc8094efe1f AS build
WORKDIR /build

COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src/main src/main

# The cache mount keeps downloaded dependencies between builds without putting them in a layer.
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw --batch-mode --no-transfer-progress -Dmaven.test.skip=true package \
    && cp target/apv-backend-*.jar /build/apv-backend.jar

# ------------------------------------------------------------------------------------------
# Run: a Java 21 runtime, the jar, and an unprivileged user.
# ------------------------------------------------------------------------------------------
FROM eclipse-temurin:21.0.13.1_2-jre-noble@sha256:897ec01ae1350622de0d5393a48a4ffffee8fe1609f248f4389578b919eaf9f5

# A fixed numeric id, so the owner of a mounted file can be reasoned about. No home, no shell.
RUN groupadd --system --gid 10001 apv \
    && useradd --system --uid 10001 --gid apv --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin apv

WORKDIR /app
COPY --from=build /build/apv-backend.jar /app/apv-backend.jar

# Inside a container the loopback address reaches nobody; which network may reach the port is
# decided by whoever runs the container. The compose file sets both of these itself.
ENV APV_BIND_ADDRESS=0.0.0.0 \
    APV_PORT=8080
EXPOSE 8080

USER 10001:10001

# Healthy only when the server answers a public read with 200: the web server is up, the
# database is reachable and migrated, and the breached-password list was accepted (the
# application does not start without it). The list is verified in full at every start, which
# takes seconds to minutes with its size and the disk, so failures do not count for the first
# five minutes; during that time the check runs every two seconds, so "healthy" comes quickly.
HEALTHCHECK --interval=15s --timeout=5s --start-period=300s --start-interval=2s --retries=3 \
    CMD curl --fail --silent --show-error --output /dev/null --max-time 4 "http://127.0.0.1:${APV_PORT:-8080}/api/site" || exit 1

# Exec form, so arguments pass through and signals reach the JVM:
#   docker run ... apv-backend                              the server
#   docker run -it ... apv-backend operator list-staff      an operator command, on the terminal
#   docker run ... apv-backend blocklist verify <file>      the list tool
ENTRYPOINT ["java", "-jar", "/app/apv-backend.jar"]
