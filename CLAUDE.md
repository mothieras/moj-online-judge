# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Build and run tests
./mvnw verify

# Run without tests
./mvnw spring-boot:run

# Run a single test class
./mvnw -Dtest=ClassName test

# Run a single test method
./mvnw -Dtest=ClassName#methodName test

# Run with specific profile (dev/prod/test)
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

Server starts on `http://localhost:8121/api`. API docs (Knife4j) at `http://localhost:8121/api/doc.html`.

## Architecture

**MOJ Backend** — an online judge platform. Spring Boot 3.4 + MyBatis Plus + MySQL + Redis.

### Layered structure

`controller` → `service` → `mapper` (MyBatis Plus), with `model/entity` for DB entities and `model/vo` for API responses.

### Judge module (`com.moj.judge`) — the core

```
controller/QuestionSubmitController  ← triggers judging
    ↓
JudgeServiceImpl                     ← orchestrator: fetch submit/question, invoke sandbox, apply strategy, persist
    ↓
┌────────────────────┬──────────────────────┐
│ CodeSandboxFactory │   JudgeManager        │
│ (factory by type)  │   (strategy dispatch) │
│                    │                       │
│ example/remote/    │ DefaultJudgeStrategy  │
│ thirdParty         │ JavaLanguageJudgeSt.. │
└────────────────────┴──────────────────────┘
```

- **`codesandbox.type`** in config selects the sandbox implementation (`example`/`remote`/`thirdParty`).
- `CodeSandboxProxy` wraps any sandbox for logging (Proxy pattern).
- `JudgeManager.doJudge()` selects a `JudgeStrategy` by language. The strategy compares expected vs actual output and returns a `JudgeInfo` result (time, memory, status message).
- `QuestionSubmit.judgeInfo` stores the result as a JSON string.

### Auth

`@AuthCheck(mustRole = "admin")` annotation on controller methods, intercepted by `AuthInterceptor` (AOP `@Around`). Roles defined in `UserRoleEnum`.

### Common response pattern

`BaseResponse<T>` wrapper. Controllers return via `ResultUtils.success(data)` or `ResultUtils.error(code, msg)`. Global exception handler in `exception/GlobalExceptionHandler`.

## Database

Execute `sql/create_table.sql` to create tables. MyBatis Plus handles CRUD; custom SQL goes in `resources/mapper/*.xml`. Entity fields use `@TableLogic` for soft delete.

## Key config properties

- `server.port`: 8121, context-path: `/api`
- `mybatis-plus.configuration.map-underscore-to-camel-case`: **false** (explicit `@TableField` mappings expected)
- Profiles: `dev` (default), `test`, `prod`
- `codesandbox.type`: `remote` (set to `example` for local testing without external sandbox)
