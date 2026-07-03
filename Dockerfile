FROM eclipse-temurin:21-jre-alpine

# 非 root 运行
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
USER appuser

WORKDIR /app

# 复制 JAR（docker-compose build 前需先 mvn package）
COPY target/*.jar app.jar

EXPOSE 8231

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
    CMD wget -qO- http://localhost:8231/api/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
