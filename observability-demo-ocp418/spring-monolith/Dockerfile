FROM registry.access.redhat.com/ubi9/openjdk-21:latest

WORKDIR /app
COPY target/demo-monolith-1.0.0.jar /app/app.jar

EXPOSE 8080
USER 185

ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
