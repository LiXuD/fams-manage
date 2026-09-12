# D6-02A task card — 生成流水线

## Status and scope

- Status at implementation handoff: `IN_REVIEW` (2026-09-13).
- Branch: `feat/D6-02A-contract-generation`; base: `8b148e3ab4117c3bee3cfb2605cfb0feeede4b22`.
- Scope: one OpenAPI 3.0.4 representative contract, deterministic Java/Spring and shared TypeScript model generation, compilation, and a minimal real-socket Spring HTTP probe.
- Explicitly out of scope: D6-02B compatibility freeze and real MySQL/Flyway/Session checks, D6-02C first business design package, remote/CI, PC or Mini Program applications, and all FAMS product business behavior.

## Candidate inputs

| Input | Fixed candidate |
|---|---|
| Java | 21 (local verification used 21.0.7) |
| Spring Boot | 4.1.1 |
| OpenAPI Generator | 7.25.0 |
| OpenAPI | 3.0.4 |
| Node / npm | 24.18.0 / 11.16.0 |
| TypeScript | 5.9.2, pinned in `package-lock.json` |
| Maven Wrapper / distribution | 3.3.4 / 3.9.16 |

These are D6-02A candidates; D6-02B owns the final compatibility freeze.

## Deliverables

- Authoritative source: `contracts/representative-probe.yaml`.
- Java generation and application boundary: `backend/`; generated interface/models are under `backend/target/generated-sources/openapi/java`.
- Shared TypeScript model package: `packages/api-types/`; generated output is under `packages/api-types/target/generated-sources/openapi/typescript`.
- Generation entry point: root `pom.xml` → `backend/pom.xml`; Maven Wrapper is at the repository root.
- Verification entry point: `scripts/verify-d6-02a.py`.

The generated TypeScript invocation sets `generateApis=false` and emits models plus the generator runtime needed by generated date conversion helpers. No generated API class is emitted or used as a Mini Program transport.

## Contract and behavior proof

`ContractProbe` covers an exact decimal-string ID above the JavaScript safe integer range, unshifted `YYYY-MM-DD`, offset date-time normalized to UTC `Z`, English enum values, integer zero, scale-preserving decimal text, ordered serials, and optional nullable `note`. Missing and explicit `null` note serialize as JSON `null`; empty note remains `""`. The handwritten controller implements the generated `ContractProbeApi` and accepts/returns only the generated `ContractProbe` model.

The real-socket test accepts the representative payload and asserts all wire values. It also accepts explicit null and empty note cases, and rejects missing required data, null quantity, numeric asset ID, invalid enum, malformed date, and malformed date-time. Rejections return the provisional common shape `code`, `message`, `requestId`, and `fieldErrors` with HTTP 400.

## TDD and verification evidence

The handwritten HTTP test was written first. The expected RED was a real `./mvnw -pl backend test` run returning exit 1 with 9 failures because the route was absent and each request received HTTP 404. After the minimal controller, JSON coercion rules, and error advice were added, the GREEN run returned exit 0 with 9 tests, 0 failures, and 0 errors.

The full command/output record, deterministic generation manifests, warnings, generated-output policy, and handoff limits are in [D6-02A generation-pipeline evidence](D6-02A-generation-pipeline.md) and the task report at `.superpowers/sdd/2026-09-12-fams-stage6-development-plan/task-d6-02a-report.md`.
