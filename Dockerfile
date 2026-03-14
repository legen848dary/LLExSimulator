# =============================================================================
# LLExSimulator — Runtime image
# =============================================================================
# The fat JAR is compiled on the HOST by 'llexsim.sh build' (./gradlew shadowJar)
# before this image is built.  The image only copies the pre-built artifact,
# so there is exactly one JAR used by Docker, local runs, and the demo client.
#
# Build sequence enforced by llexsim.sh:
#   1. ./gradlew shadowJar          →  build/libs/LLExSimulator-1.0-SNAPSHOT.jar
#   2. docker compose build         →  copies that JAR into the image
# =============================================================================

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# curl for health-check; numactl for optional CPU pinning on bare metal
RUN apt-get update && \
    apt-get install -y --no-install-recommends numactl curl && \
    rm -rf /var/lib/apt/lists/*

# Pre-built by the host Gradle invocation in llexsim.sh build
COPY build/libs/LLExSimulator-1.0-SNAPSHOT.jar app.jar

# Classpath-default config; overridden at runtime via ./config volume mount
COPY src/main/resources/simulator.properties config/simulator.properties

# Log directories — the ./logs bind-mount overlays these at runtime,
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
# --add-exports                     Required by newer Agrona/Artio for Unsafe access
# --add-opens                       Required by Agrona/Aeron for NIO internal access
ENV JAVA_OPTS="\
  -XX:+UseZGC \
  -XX:+ZGenerational \
  -Xms512m -Xmx512m \
  -XX:+AlwaysPreTouch \
  -XX:+DisableExplicitGC \
  -XX:+PerfDisableSharedMem \
  -Daeron.dir=/dev/shm/aeron-llexsim \
  -Daeron.ipc.term.buffer.length=8388608 \
  -Daeron.threading.mode=DEDICATED \
  -Daeron.sender.idle.strategy=noop \
  -Daeron.receiver.idle.strategy=noop \
  -Dagrona.disable.bounds.checks=true \
  --add-exports java.base/jdk.internal.misc=ALL-UNNAMED \
  --add-opens java.base/sun.nio.ch=ALL-UNNAMED \
  --add-opens java.base/java.nio=ALL-UNNAMED \
  --add-opens java.base/java.lang=ALL-UNNAMED"

# FIX acceptor port  |  Vert.x HTTP port
EXPOSE 9880 8080

# Note: on bare metal add: numactl --cpunodebind=0 --membind=0 before java
CMD ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
