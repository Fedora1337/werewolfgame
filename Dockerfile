# Bước 1: Build ứng dụng
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Chỉ copy file cấu hình trước để tận dụng cache của Docker
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .

# Cấp quyền và tải dependencies (chỉ tải, chưa build)
RUN chmod +x ./gradlew
RUN ./gradlew --version

# Copy code nguồn
COPY src src

# Build dự án với giới hạn RAM tối đa
# -Xmx256m giúp Gradle không ngốn quá 512MB của Render
RUN ./gradlew clean assemble --no-daemon -Dorg.gradle.jvmargs="-Xmx256m"

# Bước 2: Chạy ứng dụng
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy file jar đã build (nằm trong build/libs)
# Lưu ý: Ktor thường tạo file jar có hậu tố -all.jar hoặc tương tự
COPY --from=build /app/build/libs/*.jar app.jar

# Mở cổng
EXPOSE 8080

# Chạy với giới hạn RAM cho JRE
CMD ["java", "-Xmx256m", "-jar", "app.jar"]
