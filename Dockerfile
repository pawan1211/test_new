FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /app

COPY pom.xml .

RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src

RUN mvn -B -DskipTests package

# Find the JAR Maven actually created and give it a fixed name
RUN JAR_FILE=$(find target -maxdepth 1 -type f -name "*.jar" ! -name "*.jar.original" | head -n 1) && \
    test -n "$JAR_FILE" && \
    cp "$JAR_FILE" /app/app.jar


FROM eclipse-temurin:21-jre

WORKDIR /app

RUN useradd --system --uid 10001 spring

RUN mkdir -p /var/lib/atelier/assets && \
    chown -R spring:spring /var/lib/atelier

COPY --from=build /app/app.jar app.jar

USER spring

EXPOSE 8080

ENTRYPOINT ["java","-XX:MaxRAMPercentage=75.0","-jar","/app/app.jar"]