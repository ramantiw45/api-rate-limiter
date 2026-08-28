# ═══════════════════════════════════════════════════════════════════════════════
# Stage 1 – Build
#   Uses the full JDK image (Alpine variant) bundled with Maven so we don't
#   need a Maven wrapper committed to the repository.
# ═══════════════════════════════════════════════════════════════════════════════
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# ── Dependency layer (cached unless pom.xml changes) ──────────────────────────
COPY pom.xml .
RUN mvn dependency:go-offline --batch-mode --quiet

# ── Source compilation & packaging ────────────────────────────────────────────
COPY src ./src
RUN mvn package -DskipTests --batch-mode --quiet


# ═══════════════════════════════════════════════════════════════════════════════
# Stage 2 – Runtime
#   Eclipse Temurin 21 JRE on Alpine: smallest secure footprint.
#   Runs as a non-root user for container security best practices.
# ═══════════════════════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-alpine AS runtime

WORKDIR /app

# Create a dedicated non-root system user/group
RUN addgroup -S gateway && adduser -S gateway -G gateway

# Copy only the executable fat JAR produced by the builder stage
COPY --from=builder /build/target/*.jar app.jar

# Hand ownership to the non-root user
RUN chown gateway:gateway app.jar

USER gateway

# Gateway listens on 8080 (matches server.port in application.yml)
EXPOSE 8080

# JVM flags:
#   -XX:+UseZGC            – low-latency, concurrent GC ideal for gateway workloads
#   -XX:+UseContainerSupport – honours cgroup CPU/memory limits (default in JDK 11+,
#                              kept explicit for clarity)
#   -Djava.security.egd    – faster SecureRandom seeding (important for UUID generation)
ENTRYPOINT ["java", \
  "-XX:+UseZGC", \
  "-XX:+UseContainerSupport", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]
