FROM registry.qunhequnhe.com/proxy/library/maven:3.9-eclipse-temurin-25 AS build

WORKDIR /workspace

# Moon builds from the repository root. Keep dependency resolution on the
# company Maven proxy and build the executable Spring Boot jar in the image.
COPY _appconfig/maven-settings.xml /root/.m2/settings.xml
COPY . .
RUN mvn -B -ntp -pl server -am -DskipTests package spring-boot:repackage \
    && cp server/target/kkrepo-server-*.jar /tmp/kkrepo.jar

FROM registry.qunhequnhe.com/proxy/library/eclipse-temurin:25-jre-jammy

WORKDIR /app

RUN mkdir -p /var/lib/jetty/logs

COPY --from=build /tmp/kkrepo.jar /app/kkrepo.jar

ENV JAVA_TOOL_OPTIONS="" \
    SPRING_PROFILES_ACTIVE=default \
    SERVER_ADDRESS=0.0.0.0

EXPOSE 8080 8081

USER root

ENTRYPOINT ["java", "-jar", "/app/kkrepo.jar"]
