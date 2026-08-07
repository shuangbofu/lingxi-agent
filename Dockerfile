FROM node:20-alpine AS frontend-build
WORKDIR /workspace/frontend
COPY frontend/package.json frontend/yarn.lock ./
RUN yarn install --frozen-lockfile
COPY frontend/ ./
RUN yarn build

FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /workspace
COPY pom.xml ./
COPY runtime/ ./runtime/
COPY backend/ ./backend/
COPY --from=frontend-build /workspace/frontend/dist ./frontend/dist
RUN mvn -B -ntp -pl backend -am package -DskipTests

FROM node:20-bookworm-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        git \
        locales \
        openjdk-17-jre-headless \
        openssh-client \
        python3 \
        python3-pip \
        python3-venv \
    && sed -i '/zh_CN.UTF-8/s/^# //g' /etc/locale.gen \
    && locale-gen \
    && rm -rf /var/lib/apt/lists/*

ENV LANG=zh_CN.UTF-8
ENV LC_ALL=zh_CN.UTF-8
WORKDIR /app
COPY --from=backend-build /workspace/backend/target/lingxi-*.jar /app/lingxi.jar
RUN mkdir -p /app/data /app/logs
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/lingxi.jar"]
