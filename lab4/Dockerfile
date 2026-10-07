FROM maven:3.9-eclipse-temurin-25 AS build

WORKDIR /app

COPY pom.xml .
RUN mvn -q -DskipTests dependency:go-offline

COPY src ./src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DincludeScope=runtime

FROM eclipse-temurin:25-jre AS server

WORKDIR /app

COPY --from=build /app/target/classes ./classes
COPY --from=build /app/target/dependency ./dependency

RUN mkdir -p /app/data

EXPOSE 9000/udp
VOLUME ["/app/data"]

ENTRYPOINT ["java", "-cp", "classes:dependency/*", "org.example.Main"]
CMD ["/app/data/exchange", "9000"]

FROM build AS tests

CMD ["mvn", "-q", "test"]
