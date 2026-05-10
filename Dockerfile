FROM eclipse-temurin:21-jre-alpine

LABEL maintainer="tfpm-address-enrichment"
LABEL description="TFPM Address Enrichment Service"

WORKDIR /app

COPY app/target/app-0.1.0-SNAPSHOT-boot.jar app.jar

EXPOSE 8080

ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar $0 $@"]
