# One container: Postgres 17 + the server, run as uid 1000 by server/docker/entrypoint.sh.
# Build context is the repo root: docker build -f server/Dockerfile .
# The base image declares VOLUME /var/lib/postgresql/data; it is unused here (the cluster lives in /data/pg),
# so `docker rm -fv` also removes that empty anonymous volume.
FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /src
COPY server/gradlew server/settings.gradle.kts /src/server/
COPY server/gradle /src/server/gradle
COPY gradle/libs.versions.toml /src/gradle/libs.versions.toml
RUN cd server && ./gradlew --no-daemon --version
COPY shared /src/shared
COPY server /src/server
RUN cd server && ./gradlew --no-daemon installDist

FROM postgres:17-bookworm
# one service user owns /data; nothing runs as root. The base image's entrypoint is replaced below.
RUN apt-get update && apt-get install -y --no-install-recommends openjdk-17-jre-headless \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -g 1000 moneybook && useradd -u 1000 -g 1000 -M -s /usr/sbin/nologin moneybook \
    && mkdir /data && chown moneybook:moneybook /data
COPY --from=build /src/server/build/install/server /app
# the whole directory, so restore.sh (added with the in-server backups) needs no Dockerfile change
COPY server/docker/ /usr/local/share/moneybook/
RUN chmod 755 /usr/local/share/moneybook/* \
    && ln -s /usr/local/share/moneybook/entrypoint.sh /usr/local/bin/entrypoint \
    && ln -s /usr/local/share/moneybook/restore.sh /usr/local/bin/restore
USER moneybook
ENV MB_DATA=/data JAVA_OPTS="-Xmx384m"
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/8080 && printf "GET /health HTTP/1.0\r\n\r\n" >&3 && grep -q "\"ok\"" <&3'
ENTRYPOINT ["/usr/local/bin/entrypoint"]
