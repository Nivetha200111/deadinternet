# Dead Internet Lens backend for hosted deployments (Render, Fly.io, ...).
# Live capture is off here: a server has no logged-in browser to drive. FalkorDB runs as a separate service.
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline
COPY src src
COPY extension/collector.js extension/content.css extension/
RUN ./mvnw -q -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/target/dead-internet-lens-0.1.0.jar app.jar
ENV CAPTURE_ENABLED=false \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
