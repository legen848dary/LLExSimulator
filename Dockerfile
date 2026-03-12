# ── Stage 1: Build ─────────────────────────────────────────────────────────────
FROM gradle:8.7-jdk21 AS build
WORKDIR /build

# Copy dependency manifests first (Docker layer cache)
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/

# Pre-download dependencies for better layer caching
RUN ./gradlew --no-daemon dependencies --quiet 2>/dev/null || true

# Copy full source and build fat JAR
COPY src/ src/
RUN ./gradlew --no-daemon shadowJar -x test

# ── Stage 2: Runtime ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy AS runtime
WORKDIR /app

# curl for health-check; numactl for optional CPU pinning on bare metal
RUN apt-get update && \
    apt-get install -y --no-install-recommends numactl curl && \
    rm -rf /var/lib/apt/lists/*

COPY --from=build /build/build/libs/LLExSimulator-1.0-SNAPSHOT.jar app.jar

# Default config (can be overridden via volume mount)
COPY src/main/resources/simulator.properties config/simulator.properties

# Log directories — the ./logs bind-mount will overlay these at runtime,
# but they must exist in the image for fallback and correct permissions.
RUN mkdir -p /app/logs/archive

# ── JVM Flags ─────────────────────────────────────────────────────────────────
# -XX:+UseZGC -XX:+ZGenerational    Java 21 Generational ZGC — sub-ms GC pauses
# -Xms512m -Xmx512m                 Fixed heap — eliminates resize safepoints
# -XX:+AlwaysPreTouch               Pre-fault heap pages at startup
# -XX:+DisableExplicitGC            Block System.gc() from third-party libraries
# -XX:+PerfDisableSharedMem         Disable JMX perf shared memory overhead
# -Daeron.*                         Aeron MediaDriver low-latency config
# -Dagrona.*                        Disable UnsafeBuffer bounds checks (prod only)
# --add-opens                       Required by Agrona/Aeron for NIO internal access
ENV JAVA_OPTS="\
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xms512m -Xmx512m \
  -XX:+AlwaysPreTouch \
  -XX:+DisableExplicitGC \
  -XX:+PerfDisableSharedMem \
  -Daeron.dir=/dev/shm/aeron-llexsim \
  -Daeron.threading.mode=DEDICATED \
  -Daeron.sender.idle.strategy=noop \
  -Daeron.receiver.idle.strategy=noop \
  -Dagrona.disable.bounds.checks=true \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED"

# FIX acceptor port  |  Vert.x HTTP port
EXPOSE 9880 8080

# Note: on bare metal add: numactl --cpunodebind=0 --membind=0 before java
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]

