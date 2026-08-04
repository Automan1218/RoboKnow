FROM eclipse-temurin:17-jre

WORKDIR /app

ARG JAR_FILE=target/RoboKnow-0.0.1-SNAPSHOT.jar
COPY ${JAR_FILE} app.jar

RUN groupadd --system app && useradd --system --gid app app
RUN chown -R app:app /app
USER app

EXPOSE 8081

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
