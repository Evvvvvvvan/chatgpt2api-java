FROM maven:3.9.9-eclipse-temurin-8 AS java-build

WORKDIR /app

COPY pom.xml ./
COPY src ./src
COPY VERSION ./
COPY config.json ./
RUN mvn -q -DskipTests package

FROM eclipse-temurin:8-jre

WORKDIR /app

ARG TARGETARCH=amd64

RUN apt-get update && apt-get install -y --no-install-recommends \
    git \
    openssl \
    curl \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

RUN if [ "$TARGETARCH" = "amd64" ]; then \
        mkdir -p /opt/curl-impersonate \
        && curl -fsSL https://github.com/lwthiker/curl-impersonate/releases/download/v0.6.1/curl-impersonate-v0.6.1.x86_64-linux-gnu.tar.gz \
        | tar -xz -C /opt/curl-impersonate \
        && find /opt/curl-impersonate -name curl_chrome110 -type f -exec ln -s {} /usr/local/bin/curl_chrome110 \; ; \
    fi

COPY --from=java-build /app/target/chatgpt2api-java-*.jar /app/chatgpt2api.jar
COPY VERSION /app/VERSION
COPY config.json /app/config.json

ENV CHATGPT2API_CURL_BIN=/usr/local/bin/curl_chrome110

EXPOSE 8000

CMD ["java", "-jar", "/app/chatgpt2api.jar"]
