FROM eclipse-temurin:25-jre-jammy

WORKDIR /app

ARG JAR_FILE=server/target/kkrepo-server-0.3.0.jar

RUN groupadd --system kkrepo \
    && useradd --system --gid kkrepo --home-dir /app --shell /usr/sbin/nologin kkrepo

COPY ${JAR_FILE} /app/kkrepo.jar

ENV JAVA_OPTS="" \
    SPRING_PROFILES_ACTIVE=default

EXPOSE 8080 8081

USER kkrepo

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/kkrepo.jar \"$@\"", "--"]
