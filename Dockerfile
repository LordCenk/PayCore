# syntax=docker/dockerfile:1

# ---- build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q package -DskipTests

# ---- split the jar into layers, so dependency layers are cached between builds ----
FROM eclipse-temurin:21-jre AS extract
WORKDIR /extract
COPY --from=build /src/target/paycore-*.jar app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --launcher --destination layers

# ---- runtime ----
FROM eclipse-temurin:21-jre
RUN groupadd --system paycore && useradd --system --gid paycore --no-create-home paycore
WORKDIR /app
COPY --from=extract /extract/layers/dependencies/ ./
COPY --from=extract /extract/layers/spring-boot-loader/ ./
COPY --from=extract /extract/layers/snapshot-dependencies/ ./
COPY --from=extract /extract/layers/application/ ./
USER paycore
EXPOSE 8080
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
    LOGGING_STRUCTURED_FORMAT_CONSOLE=ecs
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
