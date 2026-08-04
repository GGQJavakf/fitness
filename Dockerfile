FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 fitness
WORKDIR /app
COPY backend/target/fitness-assistant-backend-*.jar app.jar
USER fitness
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
