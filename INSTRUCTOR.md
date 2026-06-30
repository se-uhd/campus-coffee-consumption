# Instructor notes

Short live demos for teaching from this codebase. Each one is a self-contained script: the
commands to run, what the audience sees, and the points to make.

## Demo: generating the frontend DTOs from the backend DTOs

**What it shows:** the frontend never hand-writes the data transfer objects (DTOs) it exchanges with the
backend. They are generated from the backend's own OpenAPI specification, so the Kotlin types and the
TypeScript types cannot drift apart. A change to a backend DTO reaches the Angular single-page application
(SPA) automatically.

**Time:** about 5 minutes (the mechanical demo alone is under 1 minute).

**The pipeline (one direction only):**

```
  Kotlin DTO            OpenAPI spec                  TypeScript DTO            re-export
  api/.../dtos/*.kt  ->  frontend/src-gen/          ->  frontend/src/app/      ->  frontend/src/app/
  (source of truth)     api-docs.json                 api/model/*.ts            models.ts
                        (captured from the live        (openapi-generator,       (components import
                         app, committed)               models only)              from here)
```

The two stages are separate Gradle tasks:

- `refreshOpenApiSpec` captures `api-docs.json` from the running backend (stage 1).
- `generateFrontendDtos` runs `scripts/generate-frontend-dtos.sh`, which runs `openapi-generator` over that
  spec to write `frontend/src/app/api/model/*.ts` (stage 2). It runs on every `gradle build` but is a fast
  no-op when the spec is unchanged (it hash-skips via `frontend/src-gen/.api-docs.hash`).

### Before you start (orientation, ~1 min)

Open these three files side by side so the audience sees the same type in three places:

1. The backend DTO: `api/src/main/kotlin/de/seuhd/campuscoffee/api/dtos/PriceUpdateDto.kt`

   ```kotlin
   data class PriceUpdateDto(
       @field:NotNull(message = "Amount is required.")
       @field:Min(value = 0, message = "Amount must not be negative.")
       @field:Max(value = MAX_MONEY_CENTS, message = "Amount is implausibly large.")
       val amountCents: Int?
   )
   ```

2. Its slice of the committed spec (search `api-docs.json` for `PriceUpdateDto`): a JSON schema with
   `amountCents` typed `integer`, marked `required`, with the min/max from the Bean Validation annotations.

3. The generated TypeScript DTO: `frontend/src/app/api/model/priceUpdateDto.ts`

   ```typescript
   export interface PriceUpdateDto {
       amountCents: number;
   }
   ```

   You can see it is non-nullable from the generated syntax: the field is `amountCents: number;`, with no
   `?` and no `| null`. Compare an optional field in `userDto.ts`, emitted as `id?: string | null;` (the
   `?` makes the property optional, the `| null` makes its value nullable). The `@NotNull` on the backend
   request field made it `required` and non-nullable in the spec (via the `requiredFieldsNotNullableCustomizer`
   in `api/.../openapi/OpenApiConfig.kt`), and the generator carried that into the TypeScript type as the
   absence of both markers.

Point out the banner at the top of every generated file (`Do not edit the class manually.`), then explain
the **re-export step**, the last stage of the pipeline. The components never import from `api/model/`
directly. Instead `frontend/src/app/models.ts` re-exports the generated types, and every component imports
its DTOs from `models.ts`. That one file does three jobs:

- It re-exports each generated DTO under its spec name (`export type { UserDto } from './api/model/userDto';`),
  giving the whole app a single import path.
- It renames a few request DTOs to the names the components already use
  (`export type { OwnExpenseDto as OwnExpenseRequest } from './api/model/ownExpenseDto';`), so a
  generator-chosen name does not leak into the components.
- It surfaces the inline enums, which the generator emits namespaced on their owning DTO (`UserDto.RoleEnum`),
  as standalone union aliases (`Role`, `ActivityEntryType`).

The payoff: regenerating `api/model/` can rename or reshape a generated file and no component import has to
change, because the components only ever see `models.ts`.

### Option A: the mechanical demo (no Docker, instant)

Show that the generated files really are generated, by deleting one and watching it come back. The script
runs the generator via `npx --no-install`, so the frontend dependencies must already be installed (run
`cd frontend && mise exec -- npm ci` once if this is a fresh clone). Run from the repository root:

```shell
# 1. Delete a generated DTO and the hash so the generator does not skip.
rm frontend/src/app/api/model/priceUpdateDto.ts frontend/src-gen/.api-docs.hash

# 2. git status now shows the file as deleted.
git status --short frontend/src/app/api/model/

# 3. Regenerate straight from the committed spec.
bash scripts/generate-frontend-dtos.sh

# 4. The file is back, byte-for-byte. No diff, because the spec did not change.
git status --short frontend/src/app/api/model/
```

**Talking point:** the input was unchanged, so the output is identical and `git status` is clean again. The
files in the tree are exactly what the generator produces, which is why a plain `npm run build` needs no
generation step yet the contract still cannot drift.

### Option B: the end-to-end demo (a backend change flows to the frontend)

This is the end-to-end version: change a Kotlin DTO and watch the TypeScript DTO change without touching
any frontend file. It needs Docker running (the refresh boots a throwaway PostgreSQL via Testcontainers).

1. Add an inert optional field to the backend DTO `PriceUpdateDto.kt` (the controller does not read it, so
   nothing else changes):

   ```kotlin
   data class PriceUpdateDto(
       @field:NotNull(message = "Amount is required.")
       @field:Min(value = 0, message = "Amount must not be negative.")
       @field:Max(value = MAX_MONEY_CENTS, message = "Amount is implausibly large.")
       val amountCents: Int?,
       /** Demo-only optional field; remove after the demo. */
       val note: String? = null
   )
   ```

2. Refresh the spec and regenerate the DTOs in one command (boots the app, captures `GET /api/api-docs`,
   normalizes it, rewrites `api-docs.json`, and runs `generateFrontendDtos`):

   ```shell
   mise exec -- gradle :application:refreshOpenApiSpec
   ```

3. Show what changed, without having edited any frontend source:

   ```shell
   git diff frontend/src-gen/api-docs.json frontend/src/app/api/model/priceUpdateDto.ts
   ```

   The spec gains an optional `note` property, and the generated interface gains `note?: string;`. The new
   field is optional in TypeScript because it is nullable and not `@NotNull` on the backend.

4. **Revert** so the demo leaves no trace:

   ```shell
   git checkout -- api/src/main/kotlin/de/seuhd/campuscoffee/api/dtos/PriceUpdateDto.kt
   mise exec -- gradle :application:refreshOpenApiSpec
   git status --short   # clean
   ```

### The test that detects drift

Explain why nobody can skip step 2. A test, `DevSystemTests."the committed OpenAPI spec matches the live
spec"`, asserts that the committed spec matches the live app: it compares `frontend/src-gen/api-docs.json`
against the running app's `GET /api/api-docs` (ignoring `info.version` and the server URL). If you change a
backend DTO but do not refresh the spec, that test fails and so does `gradle build`. So the generated
frontend types are always current, and `refreshOpenApiSpec` is the only command you run to update them.

**In one line:** the backend Kotlin DTOs are the single source of truth. The OpenAPI spec is captured from
the live app, the TypeScript DTOs are generated from that spec, and a test that fails the build when they
disagree keeps all three consistent.
