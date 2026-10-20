# Built by the Home Assistant Supervisor on install/update, from the code in this folder only.
# Base images are pinned by digest (multi-arch). Nothing is downloaded from other Git repositories at build time; library versions are fixed in pom.xml.

FROM maven:3.9-eclipse-temurin-21@sha256:99e61abcff91a9b1333463bd8451fb18495d6eba9250ac66a338b518f8278320 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:21-jre-noble@sha256:35501d3f93173b420ac9001658eb68157e813316b865087e345c187fca422c4c
WORKDIR /app

COPY --from=build /src/target/ha-devi-mqtt.jar /app/ha-devi-mqtt.jar
COPY auto-discovery-templates /app/auto-discovery-templates
COPY entrypoint.sh /entrypoint.sh
RUN chmod 755 /entrypoint.sh \
    && find /app/auto-discovery-templates -type f ! -name '*.json' -delete

ENTRYPOINT ["/entrypoint.sh"]

LABEL io.hass.type="addon" \
      io.hass.arch="aarch64|amd64"
