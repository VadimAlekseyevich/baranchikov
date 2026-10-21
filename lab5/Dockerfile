FROM maven:3.9-eclipse-temurin-25-alpine

WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime

CMD ["mvn", "-q", "test"]
