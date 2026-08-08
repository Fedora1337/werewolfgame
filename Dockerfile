# Bước 1: Build ứng dụng
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app

# Copy các file cấu hình Gradle
COPY gradlew .
COPY gradle gradle
COPY build.gradle.kts .
COPY settings.gradle.kts .
COPY gradle.properties .

# Cấp quyền thực thi cho gradlew
RUN chmod +x ./gradlew

# Copy code nguồn và resources
COPY src src

# Build dự án (bỏ qua chạy test để nhanh hơn)
RUN ./gradlew build -x test

# Bước 2: Chạy ứng dụng
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Copy file jar đã build từ bước 1 sang
COPY --from=build /app/build/libs/*.jar app.jar

# Mở cổng (Render sẽ tự động gán PORT)
EXPOSE 8080

# Lệnh chạy game
CMD ["java", "-jar", "app.jar"]
