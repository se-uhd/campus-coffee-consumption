# `src-gen/`: the OpenAPI spec that drives the frontend DTO codegen

`api-docs.json` is the backend's OpenAPI spec, exported from the running application. It is the **input**
to the frontend DTO codegen: `scripts/generate-frontend-dtos.sh` runs `openapi-generator` over it to
produce the TypeScript DTOs under `frontend/src/app/api/model/`, which `frontend/src/app/models.ts`
re-exports. The Gradle `generateFrontendDtos` task runs that script on every build (a fast no-op when the
spec is unchanged, gated by `.api-docs.hash`).

This file is **committed** (it is the codegen input) and must be **refreshed whenever the REST API
changes** (a new/changed DTO field, endpoint, or enum value). The generated `src/app/api/model/` files are
committed too, so a standalone `npm run build` works without a generation step.

## Refreshing the spec

The spec is served by the running app at `GET /api/api-docs` (springdoc, enabled in the `dev` profile). A
PostgreSQL database must be running on `:5432` (see the project `README`/`CLAUDE.md`).

```shell
# from the repository root
gradle :application:bootJar -x frontendBuild -x frontendInstall
java -jar application/build/libs/application.jar --spring.profiles.active=dev &   # dev has an insecure JWT fallback
# wait until http://localhost:8080/actuator/health is UP, then:
curl -s http://localhost:8080/api/api-docs > frontend/src-gen/api-docs.json
# stop the app (e.g. kill the background java process)
```

Then regenerate and rebuild:

```shell
scripts/generate-frontend-dtos.sh   # regenerates because the spec hash changed
cd frontend && npm run build
```

If the regenerated DTOs no longer match how the Angular components use them, fix the **backend** DTO's
springdoc/`@Schema`/Bean-Validation annotations so the spec is correct (e.g. a `@NotNull` request field is
emitted as required and non-nullable by the `requiredFieldsNotNullableCustomizer` in
`api/.../openapi/OpenApiConfig.kt`), rather than hand-editing the generated files.
