# syntax=docker/dockerfile:1

# ---------------------------------------------------------------------------
# Stage 1 -- build
#
# Dependencies resolve in their own layer so editing source does not re-download
# the world. The cache mount keeps repeat builds fast without baking ~/.m2 into
# any published layer.
# ---------------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /src

COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q dependency:go-offline

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    mvn -B -q clean package -DskipTests

# Explode the fat jar into its layers. Copying these separately into the runtime
# image means a code change rebuilds one small layer instead of re-pushing 30MB
# of unchanged Spring dependencies on every deploy.
RUN java -Djarmode=tools -jar target/wallet-p2p-*.jar extract --layers --destination /out

# ---------------------------------------------------------------------------
# Stage 2 -- runtime
#
# JRE rather than the full JDK, and alpine rather than a distroless Java base:
# the HEALTHCHECK needs *some* way to run, and spawning a second JVM every 30s to
# probe the first one costs ~100MB on a 512MB free tier. Alpine's busybox wget
# does it for nothing.
# ---------------------------------------------------------------------------
FROM eclipse-temurin:21-jre-alpine AS runtime

# A real unprivileged user. The container cannot become root: there is no setuid
# binary here and the app never needs one.
RUN addgroup -S -g 10001 wallet \
 && adduser  -S -u 10001 -G wallet -h /app -s /sbin/nologin wallet

WORKDIR /app

COPY --from=build --chown=wallet:wallet /out/dependencies/           ./
COPY --from=build --chown=wallet:wallet /out/spring-boot-loader/     ./
COPY --from=build --chown=wallet:wallet /out/snapshot-dependencies/  ./
COPY --from=build --chown=wallet:wallet /out/application/            ./

USER wallet:wallet

ENV PORT=8080
# MaxRAMPercentage, not -Xmx: the JVM must size its heap from the *container's*
# cgroup limit, which differs between a laptop and a 512MB free-tier instance.
# Without this the JVM reads the host's memory and gets OOM-killed on deploy.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Xss512k"

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD wget -q --spider "http://127.0.0.1:${PORT}/healthz" || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/wallet-p2p-1.0.0.jar"]
