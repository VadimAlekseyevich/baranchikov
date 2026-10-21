FROM maven:3.9-eclipse-temurin-25
WORKDIR /app
COPY pom.xml ./
COPY src ./src
RUN mvn -B -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime
CMD ["mvn", "-B", "test"]
