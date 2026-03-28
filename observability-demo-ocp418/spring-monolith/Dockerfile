# Multi-stage: compile inside the image so OpenShift DockerStrategy + Git source works
# (no pre-built target/ directory in the repository).
FROM registry.access.redhat.com/ubi9/openjdk-21:latest AS build
WORKDIR /workspace
USER root
COPY mvnw mvnw.cmd pom.xml ./
COPY .mvn .mvn
COPY src ./src
RUN chmod +x mvnw && ./mvnw -B -DskipTests package

FROM registry.access.redhat.com/ubi9/openjdk-21:latest
WORKDIR /app
COPY --from=build /workspace/target/demo-monolith-1.0.0.jar /app/app.jar
EXPOSE 8080
USER 185
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
