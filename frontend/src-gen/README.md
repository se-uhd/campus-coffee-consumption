# `src-gen/`: the OpenAPI spec that drives the frontend DTO codegen

`api-docs.json` is the backend's OpenAPI spec, exported from the running application. It is the **input**
to the frontend DTO codegen: `scripts/generate-frontend-dtos.sh` runs `openapi-generator` over it to
produce the TypeScript DTOs under `frontend/src/app/api/model/`, which `frontend/src/app/models.ts`
re-exports. The Gradle `generateFrontendDtos` task runs that script on every build (a fast no-op when the
spec is unchanged, gated by `.api-docs.hash`).

This file is **committed** (it is the codegen input) and must be **refreshed whenever the REST API
changes** (a new/changed DTO field, endpoint, or enum value). The generated `src/app/api/model/` files are
committed too, so a standalone `npm run build` works without a generation step. A **drift gate** enforces
the refresh: `DevSystemTests."the committed OpenAPI spec matches the live spec"` fails `gradle build` when
this file diverges from the running app's spec.

## Refreshing the spec

Run one command (Docker must be running for the Testcontainers PostgreSQL the gate boots):

```shell
gradle :application:refreshOpenApiSpec
```

It boots the app under the `dev` profile against a throwaway database, captures `GET /api/api-docs`,
normalizes it (`info.version` is pinned to a placeholder and the `servers` block is dropped, so the spec is
not coupled to the release number or the server URL), writes it to `api-docs.json`, and regenerates the
DTOs. Commit the updated `api-docs.json` and the regenerated `src/app/api/model/` files.

If the regenerated DTOs no longer match how the Angular components use them, fix the **backend** DTO's
springdoc/`@Schema`/Bean-Validation annotations so the spec is correct (e.g. a `@NotNull` request field is
emitted as required and non-nullable by the `requiredFieldsNotNullableCustomizer` in
`api/.../openapi/OpenApiConfig.kt`), rather than hand-editing the generated files.
