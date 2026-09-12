# D6-02A generation-pipeline evidence

## Authoritative inputs and generated boundaries

The only contract input is `contracts/representative-probe.yaml` (`openapi: 3.0.4`). `backend/pom.xml` invokes OpenAPI Generator `7.25.0` for both outputs. The Spring invocation uses `generatorName=spring`, `library=spring-boot`, `useSpringBoot4=true`, `useJackson3=true`, `useJakartaEe=true`, `interfaceOnly=true`, and `useTags=true`. The 7.25.0 migration-sensitive options are explicit: `generateJsonIncludeAnnotations=false`, `generateJsonSetterNullsAnnotations=false`, and `openApiNullable=false`. Application-level Jackson configuration and generated Bean Validation therefore own this probe's inclusion and null behavior.

Java output is generated below `backend/target/generated-sources/openapi/java` and includes `ContractProbeApi`, `ContractProbe`, and `ErrorResponse`. The handwritten controller in `backend/src/main/java/com/fams/contract/probe/` implements the generated interface and uses generated models without parallel DTOs.

The shared TypeScript package is rooted at `packages/api-types/`. Its `typescript-fetch` invocation uses the versioned templates in `packages/api-types/openapi-templates/typescript-fetch/`, `dateLibrary=string`, `generateApis=false`, `generateModels=true`, and `generateSupportingFiles=false`. The templates intentionally emit only pure model interfaces/enums; the output contains two model files plus the generator metadata hash, no `runtime.ts`, no `*Api.ts`, no root transport index, and no Fetch/API transport. `packages/api-types/src/index.ts` exports model types only. `tsconfig.json` sets `lib: ["ES2022"]`, so the Node 24 type-check does not obtain DOM declarations implicitly. The generator name is retained for its OpenAPI model input, not used as a Mini Program transport.

Both generated roots are under ignored `target/` directories and are recreated from the contract, fixed POM configuration, and versioned templates. Before either clean generation, the verifier snapshots any existing Java/TypeScript tree and compares it with the authoritative first generation. Its executable fixtures reject Java content edits, TypeScript content edits, and stale extra TypeScript files; an absent pre-existing tree is accepted. The Maven entry point is Wrapper `3.3.4` with distribution `3.9.16`. The package workspace pins Node `24.18.0`, npm `11.16.0`, and TypeScript `5.9.2` in `package-lock.json`.

## RED → GREEN

The test `backend/src/test/java/com/fams/contract/ContractProbeHttpTest.java` was written before handwritten application behavior. The portable form of the expected RED command was:

```text
rtk run './mvnw -pl backend test'
```

With Java 21 supplied by the caller, it returned exit `1`: `Tests run: 9, Failures: 9, Errors: 0`; the absent route returned HTTP `404` for the positive and rejection requests. This was the intended missing-behavior RED, not a generated-code compilation failure.

After implementing only the controller, application-level Jackson coercion/inclusion configuration, and common error advice, the same real-socket test returned exit `0`: `Tests run: 9, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

## HTTP cases

The positive payload preserves `assetId` `9007199254740993`, `businessDate` `2026-09-13`, `quantity` `0`, `amount` `100.00`, and the complete serial order `SN-SECOND`, `SN-FIRST`; the `+08:00` instant is returned as `2026-09-13T00:15:00Z`. Assertions check field presence and JSON types before values. Missing and explicit `null` `note` return JSON `null`; empty `note` returns an empty string.

The parameterized rejection cases are missing required data, explicit null required integer, numeric JSON ID, invalid enum, malformed date (`2026-02-30`), and malformed date-time. Date negatives replace only their target property, so the other temporal field cannot mask the failure. Each uses a listening random-port server and structurally checks HTTP 400 plus `code`, `message`, nonblank `requestId`, and object `fieldErrors`.

## Verification run

The portable versioned command below performs the checks in order. The caller must place Java 21, Node 24.18.0, and npm 11.16.0 on `PATH`; the local report records the exact machine paths used for the evidence.

```text
rtk run 'python3 scripts/verify-d6-02a.py'
```

1. Java, Node, npm, and Maven Wrapper version checks.
2. OpenAPI validation and two generations from empty Java and TypeScript output roots.
3. SHA-256 manifests for both full generated trees; the second run must have zero differences.
4. Existing-output equivalence plus Java/TypeScript edit and stale-file rejection fixtures.
5. No generated files tracked by Git, generator markers present, wire dates remain strings, no DOM lib, and no API/runtime/transport files or tokens.
6. Java compile, clean locked Node install, TypeScript type-check, real-socket HTTP tests, commit-message behavior suite, and `git diff --check`.

The successful review-fix run used Java `21.0.7`, Node `v24.18.0`, npm `11.16.0`, Maven `3.9.16`, and reported:

```text
[OpenAPI validation] exit=0: Spec has no errors. / No validation issues detected.
[pre-existing generated tree] java=equivalent before discard; typescript=equivalent before discard
[generated negative case] Java content edit=REJECTED
[generated negative case] TypeScript content edit=REJECTED
[generated negative case] TypeScript stale extra file=REJECTED
[generated negative case] empty pre-existing tree=ACCEPTED
[clean generation 1] exit=0: OpenAPI Generator spring and typescript-fetch; BUILD SUCCESS
[clean generation 2] exit=0: OpenAPI Generator spring and typescript-fetch; BUILD SUCCESS
[determinism] Java files=4, TypeScript files=3, differences=0
[generated roots] ... targets ignored, no generated files tracked, markers present, wire dates are strings, no transport/runtime
[Java compile] exit=0: BUILD SUCCESS
[locked Node install] exit=0: added 2 packages, audited 4 packages; found 0 vulnerabilities
[TypeScript typecheck] exit=0: TypeScript: No errors found
[real-socket HTTP tests] exit=0: Tests run: 9, Failures: 0, Errors: 0; BUILD SUCCESS
[commit-message suite] exit=0
[diff check] exit=0
D6-02A verification passed.
```

## Warnings and limits

- OpenAPI Generator emitted informational post-processing hints because `JAVA_POST_PROCESS_FILE` and `TS_POST_PROCESS_FILE` are not configured; generated output remained deterministic.
- Generator log entries for model documentation/supporting-file skips are expected from the explicit `generateSupportingFiles=false` and documentation options; no skipped file is part of the shared model surface.
- The custom TypeScript templates intentionally bypass the generator's supporting files so the shared package remains transport-free; this is a D6-02A boundary decision, not a final client architecture.
- Javac reported deprecated API use in generated `ContractProbe`; this is supplier output and was not hand-edited.
- Spring Boot test emitted Mockito dynamic-agent/JDK future-warning messages; tests passed. This probe does not configure a Mockito agent because it does not use mocks.
- The candidate combination is not a D6-02B compatibility freeze. There is no database, migration, session, remote repository, CI, PC app, Mini Program app, or product business implementation in this task. The handoff remains `IN_REVIEW` pending independent review; next is D6-02B, not D6-02A.
