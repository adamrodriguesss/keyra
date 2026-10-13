# syntax=docker/dockerfile:1
# Build: docker build -t keyra .   Configuration comes only from KEYRA_* env vars (see README).

FROM eclipse-temurin:21-jdk AS build
WORKDIR /build
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY src/main src/main
RUN --mount=type=cache,target=/root/.m2 ./mvnw -q -Dmaven.test.skip=true package \
    && cp target/keyra-*.jar keyra.jar \
    && java -Djarmode=tools -jar keyra.jar extract --layers --destination extracted

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --no-create-home keyra
WORKDIR /app
# Least-often-changing layers first, so rebuilds reuse the cached dependency layers.
COPY --from=build /build/extracted/dependencies/ ./
COPY --from=build /build/extracted/spring-boot-loader/ ./
COPY --from=build /build/extracted/snapshot-dependencies/ ./
COPY --from=build /build/extracted/application/ ./
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "keyra.jar"]
