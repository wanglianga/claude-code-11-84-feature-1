# 后端镜像：多阶段构建（Maven 构建 → JRE 运行）
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline || true
COPY src ./src
RUN mvn -q -B clean package -DskipTests

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /build/target/field-gov.jar app.jar
EXPOSE 8080
ENV JAVA_OPTS=""
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
