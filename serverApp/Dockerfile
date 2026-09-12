# CNCVerse Bridge Server — headless Linux edition
# Build:  docker build -f serverApp/Dockerfile -t cncverse-bridge-server .
# Run:    docker run -d -p 8080:8080 -v cncverse-data:/config \
#             -e CNC_ADMIN_TOKEN=change-me cncverse-bridge-server

FROM gradle:8.13-jdk21 AS build
WORKDIR /src
# Cache the wrapper + dependencies first
COPY gradlew gradle/ ./
RUN ./gradlew --version >/dev/null 2>&1 || true
COPY . .
# Build the headless server distribution (compiles :shared desktop + :serverApp)
RUN ./gradlew :serverApp:installDist --console=plain

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/serverApp/build/install/cncverse-bridge-server/ ./

# Persistent config (repos, plugin settings, cloudflared, .cs3 cache)
ENV CNC_CONFIG_DIR=/config
ENV CNC_CACHE_DIR=/config/bridge
VOLUME /config
EXPOSE 8080

# JVM: container-aware memory + headless (no AWT ever needed)
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75.0 -Djava.awt.headless=true -Dfile.encoding=UTF-8"

ENTRYPOINT ["./bin/cncverse-bridge-server"]
