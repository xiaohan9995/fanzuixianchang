FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app app
RUN mkdir -p /app/data/ops && chown -R app:app /app/data
COPY --from=build --chown=app:app /build/target/fanzuixianchang-api-0.1.0.jar /app/app.jar
USER app
ENV SERVER_ADDRESS=0.0.0.0
ENV PORT=80
ENV OPS_DATA_DIR=/app/data/ops
ENV OPS_STORAGE=mysql
EXPOSE 80
ENTRYPOINT ["java","-jar","/app/app.jar"]
