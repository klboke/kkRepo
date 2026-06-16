FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

ARG JAR_FILE=server/target/nexus-plus-server-0.1.0.jar

RUN groupadd --system nexusplus \
    && useradd --system --gid nexusplus --home-dir /app --shell /usr/sbin/nologin nexusplus

COPY ${JAR_FILE} /app/nexus-plus.jar

ENV JAVA_OPTS="" \
    SPRING_PROFILES_ACTIVE=default

EXPOSE 8080 8081

USER nexusplus

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/nexus-plus.jar \"$@\"", "--"]
