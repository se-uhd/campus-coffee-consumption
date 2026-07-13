/**
 * Mirrors the backend response/request DTOs.
 *
 * These types are GENERATED from the backend OpenAPI spec (`frontend/src-gen/api-docs.json`) by
 * openapi-generator into `./api/model`, and re-exported here so components import from a single path
 * (`../models` / `../../models`) instead of reaching into the generated tree. Regenerate with
 * `scripts/generate-frontend-dtos.sh` (also run by the Gradle build). Do not hand-edit the generated
 * files under `api/model/`.
 *
 * Every type is re-exported under its exact generated name, so the frontend vocabulary matches the
 * backend DTOs. Frontend-only types that have no spec counterpart (e.g. the activity-list
 * `ActivityFilter`) stay hand-written in their component.
 */

// --- Generated DTOs, re-exported under their spec names ------------------------------------------
export type { UserDto } from './api/model/userDto';
export type { ConsumptionDto } from './api/model/consumptionDto';
export type { ConsumptionChangeDto } from './api/model/consumptionChangeDto';
export type { ConsumptionDeltaDto } from './api/model/consumptionDeltaDto';
export type { ConsumptionOverrideDto } from './api/model/consumptionOverrideDto';
export type { TokenRequestDto } from './api/model/tokenRequestDto';
export type { TokenResponseDto } from './api/model/tokenResponseDto';
export type { PublicKeyDto } from './api/model/publicKeyDto';
export type { TotpEnrollmentDto } from './api/model/totpEnrollmentDto';
export type { TotpActivateRequestDto } from './api/model/totpActivateRequestDto';
export type { TotpStatusDto } from './api/model/totpStatusDto';
export type { ActivityEntryDto } from './api/model/activityEntryDto';
export type { GlobalActivityEntryDto } from './api/model/globalActivityEntryDto';
export type { UserSummaryDto } from './api/model/userSummaryDto';
export type { PriceDto } from './api/model/priceDto';
export type { PriceChangeDto } from './api/model/priceChangeDto';
export type { ExpenseDto } from './api/model/expenseDto';
export type { PaymentDto } from './api/model/paymentDto';
export type { KittyDto } from './api/model/kittyDto';
export type { UserBalanceDto } from './api/model/userBalanceDto';
export type { CoffeeBeanDto } from './api/model/coffeeBeanDto';
export type { CoffeeBeanRatingsDto } from './api/model/coffeeBeanRatingsDto';
export type { CoffeeRatingPromptDto } from './api/model/coffeeRatingPromptDto';

// --- Generated request DTOs (outbound request bodies), under their spec names --------------------
export type { OwnExpenseDto } from './api/model/ownExpenseDto';
export type { AdminExpenseDto } from './api/model/adminExpenseDto';
export type { RatingRequestDto } from './api/model/ratingRequestDto';
export type { PriceUpdateDto } from './api/model/priceUpdateDto';
export type { DepositRequestDto } from './api/model/depositRequestDto';
export type { AdjustmentRequestDto } from './api/model/adjustmentRequestDto';
export type { ProfileUpdateDto } from './api/model/profileUpdateDto';

// --- Enums, re-exported from their standalone generated modules ----------------------------------
// The backend emits each enum as a named schema (springdoc's enumsAsRef), so the generator produces a
// standalone module per enum with a clean union. Re-export as a type, except ExpenseType, which is also
// used as a runtime value (`ExpenseType.Beans`).
export type { Role } from './api/model/role';
export type { ActivityEntryType } from './api/model/activityEntryType';
export type { SummaryPanel } from './api/model/summaryPanel';
export { ExpenseType } from './api/model/expenseType';
