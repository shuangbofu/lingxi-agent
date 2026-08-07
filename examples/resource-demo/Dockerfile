FROM node:20-alpine AS frontend-build
WORKDIR /workspace/resource-demo/frontend
COPY resource-demo/frontend/package.json resource-demo/frontend/yarn.lock ./
RUN yarn install --frozen-lockfile
COPY resource-demo/frontend/ ./
RUN yarn build

FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /workspace/resource-demo/backend
COPY resource-demo/backend/pom.xml ./
COPY resource-demo/skills /workspace/resource-demo/skills
RUN mvn -B -ntp dependency:go-offline
COPY resource-demo/backend/src ./src
COPY resource-demo/scenarios /workspace/resource-demo/scenarios
COPY --from=frontend-build /workspace/resource-demo/frontend/dist /workspace/resource-demo/frontend/dist
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=backend-build /workspace/resource-demo/backend/target/lingxi-resource-demo-*.jar /app/resource-demo.jar
EXPOSE 8091
ENTRYPOINT ["java", "-jar", "/app/resource-demo.jar"]
