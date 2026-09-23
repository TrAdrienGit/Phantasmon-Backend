# Backend TDD loop

1. Read the CAD section + OpenAPI path + `PHANTASMON_DB_SCHEMA.md` for the feature.
2. Write a failing test (unit for pure rules, Testcontainers for persistence/JSONB/unique indexes).
3. Implement the minimum production code.
4. Re-run the relevant tests, then `./gradlew test` before considering the feature done.
5. Do not add Redis, H2, or Minecraft libraries to make a test "easier".
