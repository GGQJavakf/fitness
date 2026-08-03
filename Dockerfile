FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /workspace
COPY backend/.mvn backend/.mvn
COPY backend/mvnw backend/pom.xml backend/
COPY backend/src backend/src
COPY contract contract
COPY rule-config rule-config
WORKDIR /workspace/backend
RUN chmod +x ./mvnw && ./mvnw -DskipTests package

FROM eclipse-temurin:21-jre-jammy
RUN useradd --system --uid 10001 fitness
WORKDIR /app
COPY --from=build /workspace/backend/target/fitness-assistant-backend-*.jar app.jar
USER fitness
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
