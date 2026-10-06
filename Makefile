.PHONY: lint test build

# Guarded on java (same pattern as sdks/python): the committed Gradle wrapper
# needs only a JDK, and machines without one no-op honestly instead of
# failing the repo-wide make lint/test.
ifeq (,$(shell command -v java 2>/dev/null))
lint test build:
	@echo "sdks/kotlin: java not installed - skipping $@"
else
# Compilation runs with allWarningsAsErrors (build.gradle.kts).
lint:
	./gradlew -q compileKotlin compileTestKotlin compileTestJava check -x test

test:
	./gradlew -q test

build:
	./gradlew -q jar conformanceJar
endif
