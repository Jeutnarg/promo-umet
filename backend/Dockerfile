FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B package

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system sandbox && useradd --system --gid sandbox sandbox
WORKDIR /app
COPY --from=build /workspace/target/promotion-sandbox-1.0.0.jar app.jar
USER sandbox
EXPOSE 8081
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
