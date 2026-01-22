# Build stage - use Debian-based image (glibc) for protoc compatibility
FROM eclipse-temurin:21.0.9_10-jdk AS builder

WORKDIR /app

# Install Maven
RUN apt-get update && apt-get install -y maven && rm -rf /var/lib/apt/lists/*

# Copy Maven files
COPY pom.xml .
COPY src ./src

# Build
RUN mvn clean package -DskipTests

# Runtime stage - Alpine 3.22 with latest JRE for security fixes
FROM eclipse-temurin:21.0.9_10-jre-alpine-3.22

WORKDIR /app

# Create non-root user
RUN addgroup -S lockmgr && adduser -S lockmgr -G lockmgr
USER lockmgr

# Copy built artifact
COPY --from=builder /app/target/lockmgr-*.jar app.jar

# Expose gRPC ports
EXPOSE 9090 9091

# Health check (TCP check on gRPC port)
HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD nc -z localhost 9090 || exit 1

# JVM options for containers
ENV JAVA_OPTS="-XX:+UseG1GC -XX:MaxRAMPercentage=75.0 -XX:+UseContainerSupport"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
