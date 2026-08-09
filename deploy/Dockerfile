FROM eclipse-temurin:21-jre-jammy

RUN groupadd --gid 10001 redblack \
    && useradd --uid 10001 --gid redblack --no-create-home --home-dir /app --shell /usr/sbin/nologin redblack

ARG JAR_FILE
COPY ${JAR_FILE} /app/application.jar
RUN chown redblack:redblack /app/application.jar

USER redblack
WORKDIR /app
ENTRYPOINT ["java", "-jar", "/app/application.jar"]
