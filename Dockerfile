# Claude: jar собирается внутри Docker, поэтому для запуска и обновления бота хватает `docker compose up -d --build`
FROM eclipse-temurin:17-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
COPY src src
# Claude: в git у gradlew нет флага исполнения
RUN chmod +x gradlew && ./gradlew --no-daemon bootJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /src/build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
