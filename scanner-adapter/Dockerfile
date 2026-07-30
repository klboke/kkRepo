FROM anchore/syft:v1.49.0@sha256:13b53ebabe3d215268c90cf8fb9b875f0183908245f376fd4b3a2cb69d21d484 AS syft
FROM anchore/grype:v0.116.0@sha256:fd4ab4d1042b522c896e73bdf09ab8bf384fa417df99d6dd0d6e1008c7e7c821 AS grype
FROM eclipse-temurin:25-jre-alpine@sha256:28db6fdf60e38945e43d840c0333aeaec66c15943070104f7586fd3c9d1665b0

LABEL org.opencontainers.image.title="kkRepo scanner adapter" \
      org.opencontainers.image.description="Isolated Syft catalog and Grype vulnerability matcher for kkRepo"

COPY --from=syft /syft /usr/local/bin/syft
COPY --from=grype /grype /usr/local/bin/grype
COPY scanner-adapter/target/kkrepo-scanner-adapter-*.jar /app/scanner-adapter.jar

RUN addgroup -S -g 10001 scanner \
    && adduser -S -D -H -u 10001 -G scanner scanner \
    && mkdir -p /tmp/kkrepo-scanner /var/lib/kkrepo-scanner/grype \
    && chown -R scanner:scanner /tmp/kkrepo-scanner /var/lib/kkrepo-scanner

USER 10001:10001
WORKDIR /app
EXPOSE 8080
VOLUME ["/var/lib/kkrepo-scanner/grype"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-Djava.io.tmpdir=/tmp", "-jar", "/app/scanner-adapter.jar"]
