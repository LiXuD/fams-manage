# D6-02A generation-pipeline evidence

## Authoritative inputs and generated boundaries

The only contract input is `contracts/representative-probe.yaml` (`openapi: 3.0.4`). `backend/pom.xml` invokes OpenAPI Generator `7.25.0` for both outputs. The Spring invocation uses `generatorName=spring`, `library=spring-boot`, `useSpringBoot4=true`, `useJackson3=true`, `useJakartaEe=true`, `interfaceOnly=true`, and `useTags=true`. The 7.25.0 migration-sensitive options are explicit: `generateJsonIncludeAnnotations=false`, `generateJsonSetterNullsAnnotations=false`, and `openApiNullable=false`. Application-level Jackson configuration and generated Bean Validation therefore own this probe's inclusion and null behavior.

Java output is generated below `backend/target/generated-sources/openapi/java` and includes `ContractProbeApi`, `ContractProbe`, and `ErrorResponse`. The handwritten controller in `backend/src/main/java/com/fams/contract/probe/` implements the generated interface and uses generated models without parallel DTOs.

TypeScript output is generated below `packages/api-types/target/generated-sources/openapi/typescript` with `typescript-fetch`, `generateApis=false`, and `generateModels=true`. The generated models and their date conversion helper runtime are a shared model surface; no API class is emitted, and the generated browser Fetch runtime is not a Mini Program transport. Both output roots are under ignored `target/` directories and are recreated from the contract and POM configuration.

The Maven entry point is Wrapper `3.3.4` with distribution `3.9.16`. The package workspace pins Node `24.18.0`, npm `11.16.0`, and TypeScript `5.9.2` in `package-lock.json`.

## RED → GREEN

The test `backend/src/test/java/com/fams/contract/ContractProbeHttpTest.java` was written before handwritten application behavior. The expected RED command was:

```text
rtk run 'export JAVA_HOME=/Users/lixd/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.7/Contents/Home; export PATH="$JAVA_HOME/bin:/opt/homebrew/bin:/usr/bin:/bin"; ./mvnw -pl backend test'
```

It returned exit `1`: `Tests run: 9, Failures: 9, Errors: 0`; the absent route returned HTTP `404` for the positive and rejection requests. This was the intended missing-behavior RED, not a generated-code compilation failure.

After implementing only the controller, application-level Jackson coercion/inclusion configuration, and common error advice, the same real-socket test returned exit `0`: `Tests run: 9, Failures: 0, Errors: 0`, `BUILD SUCCESS`.

## HTTP cases

The positive payload preserves `assetId` `9007199254740993`, `businessDate` `2026-09-13`, `quantity` `0`, `amount` `100.00`, and serial order `SN-SECOND`, `SN-FIRST`; the `+08:00` instant is returned as `2026-09-13T00:15:00Z`. Missing and explicit `null` `note` return JSON `null`; empty `note` returns an empty string.

The parameterized rejection cases are missing required data, explicit null required integer, numeric JSON ID, invalid enum, malformed date (`2026-02-30`), and malformed date-time. Each uses a listening random-port server and structurally checks HTTP 400 plus `code`, `message`, nonblank `requestId`, and object `fieldErrors`.

## Verification run

The versioned command below performs these checks in order (the Java/Node paths are local execution evidence and are not embedded in the script):

```text
rtk run 'export JAVA_HOME=/Users/lixd/Library/Java/JavaVirtualMachines/graalvm-jdk-21.0.7/Contents/Home; export PATH="$JAVA_HOME/bin:/Users/lixd/.nvm/versions/node/v24.18.0/bin:/opt/homebrew/bin:/usr/bin:/bin"; python3 scripts/verify-d6-02a.py'
```

1. Java, Node, npm, and Maven Wrapper version checks.
2. OpenAPI validation and two clean generation runs.
3. SHA-256 manifests for both generated roots; the second run must have zero differences.
4. No generated files tracked by Git and generator markers present in generated sources.
5. Java compile, TypeScript type-check, real-socket HTTP tests, commit-message behavior suite, and `git diff --check`.

The successful run used Java `21.0.7`, Node `v24.18.0`, npm `11.16.0`, Maven `3.9.16`, and reported:

```text
[OpenAPI validation] exit=0: Spec has no errors. / No validation issues detected.
[clean generation 1] exit=0: OpenAPI Generator spring and typescript-fetch; BUILD SUCCESS
[clean generation 2] exit=0: OpenAPI Generator spring and typescript-fetch; BUILD SUCCESS
[determinism] Java files=4, TypeScript files=9, differences=0
[generated roots] ... targets ignored, no generated files tracked, generator markers present
[Java compile] exit=0: BUILD SUCCESS
[TypeScript typecheck] exit=0: TypeScript: No errors found
[real-socket HTTP tests] exit=0: Tests run: 9, Failures: 0, Errors: 0; BUILD SUCCESS
[commit-message suite] exit=0
[diff check] exit=0
D6-02A verification passed.
```

## Warnings and limits

- OpenAPI Generator emitted informational post-processing hints because `JAVA_POST_PROCESS_FILE` and `TS_POST_PROCESS_FILE` are not configured; generated output remained deterministic.
- Javac reported deprecated API use in generated `ContractProbe`; this is supplier output and was not hand-edited.
- Spring Boot test emitted Mockito dynamic-agent/JDK future-warning messages; tests passed. This probe does not configure a Mockito agent because it does not use mocks.
- The candidate combination is not a D6-02B compatibility freeze. There is no database, migration, session, remote repository, CI, PC app, Mini Program app, or product business implementation in this task.
