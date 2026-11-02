# Build stage: Red Hat UBI9 + OpenJDK 21 (official base image)
FROM registry.access.redhat.com/ubi9/openjdk-21:latest AS build
USER root
RUN microdnf install -y maven && microdnf clean all
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src ./src
RUN mvn package -DskipTests -q

# Runtime stage: UBI9 runtime only (minimal, no JDK tools)
FROM registry.access.redhat.com/ubi9/openjdk-21-runtime:latest
WORKDIR /app
COPY --from=build /app/target/helloworld-java-1.0.0.jar app.jar
EXPOSE 8080
# Default USER 185 in ubi9/openjdk-21-runtime is OpenShift-compatible
ENTRYPOINT ["java", "-jar", "app.jar"]
