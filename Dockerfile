FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn --batch-mode --no-transfer-progress dependency:go-offline
COPY src ./src
RUN mvn --batch-mode --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN useradd --system --uid 10001 agentlink
COPY --from=build /build/target/agentlink-0.1.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8096
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
