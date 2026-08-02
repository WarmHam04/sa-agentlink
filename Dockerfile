FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/agentlink-0.1.0-SNAPSHOT.jar app.jar
EXPOSE 8096
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
