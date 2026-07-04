# ── Stage 1: Build ────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-21-alpine AS builder

WORKDIR /build

# 先复制 pom.xml，利用 Docker 层缓存加速依赖下载
COPY pom.xml .
RUN mvn dependency:go-offline -B -q

# 复制源码并编译打包（skipTests，CI 里单独跑）
COPY src/ src/
RUN mvn package -DskipTests -B -q

# ── Stage 2: Runtime ──────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine

# HEALTHCHECK 需要 wget
RUN apk add --no-cache wget && \
    addgroup -S appgroup && adduser -S appuser -G appgroup

WORKDIR /app

# 从构建阶段复制 JAR
COPY --from=builder /build/target/*.jar app.jar

# 非 root 运行
USER appuser

EXPOSE 8231

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8231/api/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
