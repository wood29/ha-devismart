# Built by the Home Assistant Supervisor on install/update, from the code in this folder only.
# Nothing is downloaded from other Git repositories at build time; library versions are fixed in pom.xml.

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-noble
WORKDIR /app

COPY --from=build /src/target/ha-devi-mqtt.jar /app/ha-devi-mqtt.jar
COPY auto-discovery-templates /app/auto-discovery-templates
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh \
    && find /app/auto-discovery-templates -type f ! -name '*.json' -delete

ENTRYPOINT ["/entrypoint.sh"]

LABEL io.hass.type="addon" \
      io.hass.arch="aarch64|amd64"
