ARG SIGNAL_CLI_VERSION=0.14.9

# ---- Build stage: compile the Java app ----
FROM docker.io/maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
ARG BOT_COMMIT=unknown
ARG SIGNAL_CLI_VERSION
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package \
    -Dbuild.commit="${BOT_COMMIT}" \
    -Dsignal.cli.version="${SIGNAL_CLI_VERSION}"

# ---- Runtime stage ----
# Must be glibc-based (not alpine/musl): signal-cli bundles a native
# libsignal-client library built for glibc x86_64 Linux.
FROM docker.io/eclipse-temurin:25-jre

# signal-cli needs to stay current: Signal's servers eventually reject releases
# older than ~3 months. Check https://github.com/AsamK/signal-cli/releases for
# the latest version and rebuild with --build-arg SIGNAL_CLI_VERSION=x.y.z.
# Current signal-cli requires at least JRE 25 (see its README), hence the base image.
ARG SIGNAL_CLI_VERSION
ARG BOT_COMMIT=unknown

RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \
    curl -fsSL -o /tmp/signal-cli.tar.gz \
      "https://github.com/AsamK/signal-cli/releases/download/v${SIGNAL_CLI_VERSION}/signal-cli-${SIGNAL_CLI_VERSION}.tar.gz" && \
    tar xf /tmp/signal-cli.tar.gz -C /opt && \
    ln -s "/opt/signal-cli-${SIGNAL_CLI_VERSION}/bin/signal-cli" /usr/local/bin/signal-cli && \
    rm /tmp/signal-cli.tar.gz && \
    apt-get purge -y curl && apt-get autoremove -y && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY --from=build /app/target/signalmodbot.jar /app/signalmodbot.jar

ENV ADMIN_TOKEN=""
ENV SUMMARY_INTERVAL_MINUTES=60
ENV DEVICE_NAME=signalmodbot
ENV TIME_ZONE=UTC
ENV DATA_DIR=/data
VOLUME /data

ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-jar", "/app/signalmodbot.jar"]
