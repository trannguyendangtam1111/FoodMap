FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY src/main ./src/main
RUN mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-jammy
RUN groupadd --gid 10001 foodmap \
    && useradd --uid 10001 --gid foodmap --no-create-home --shell /usr/sbin/nologin foodmap
WORKDIR /app
COPY --from=build --chown=10001:10001 /build/target/foodmap-backend-1.0.0-SNAPSHOT.jar ./app.jar
USER 10001:10001
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0"
EXPOSE 10000
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.profiles.active=prod"]
