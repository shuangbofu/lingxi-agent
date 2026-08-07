.PHONY: setup validate test build backend frontend

setup:
	cd frontend && yarn install --frozen-lockfile

validate:
	python3 scripts/validate_definitions.py

test: validate
	mvn -B -ntp -pl backend -am test
	cd frontend && yarn build

build: validate
	cd frontend && yarn build
	mvn -B -ntp -pl backend -am package -DskipTests

backend:
	mvn -B -ntp -pl backend -am install -DskipTests
	mvn -pl backend spring-boot:run

frontend:
	cd frontend && yarn dev
