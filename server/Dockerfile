# Build context is the repo root: docker build -f server/Dockerfile .
FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /src
COPY server/gradlew server/settings.gradle.kts /src/server/
COPY server/gradle /src/server/gradle
COPY gradle/libs.versions.toml /src/gradle/libs.versions.toml
RUN cd server && ./gradlew --no-daemon --version
COPY shared /src/shared
COPY server /src/server
RUN cd server && ./gradlew --no-daemon installDist

FROM eclipse-temurin:17-jre-noble
# the base image already has uid/gid 1000 ("ubuntu"); replace it with a fixed service user
RUN userdel -r ubuntu 2>/dev/null; \
    groupadd -g 1000 moneybook && useradd -u 1000 -g 1000 -M -s /usr/sbin/nologin moneybook \
    && mkdir /data && chown moneybook:moneybook /data
COPY --from=build /src/server/build/install/server /app
USER moneybook
ENV MB_DATA=/data
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
