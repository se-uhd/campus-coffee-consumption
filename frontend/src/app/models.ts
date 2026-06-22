/**
 * Mirrors the backend response/request DTOs.
 *
 * These types are GENERATED from the backend OpenAPI spec (`frontend/src-gen/api-docs.json`) by
 * openapi-generator into `./api/model`, and re-exported here so components keep importing from
 * `../models` / `../../models`. Regenerate with `scripts/generate-frontend-dtos.sh` (also run by the
 * Gradle build). Do not hand-edit the generated files under `api/model/`.
 *
 * A few re-exports rename a generated type to the name the components already use, and the two inline
 * enums are surfaced as standalone union aliases (`Role`, `LedgerEntryType`). Frontend-only types that
 * have no spec counterpart (e.g. the ledger-list `LedgerFilter`) stay hand-written, below.
 */

// --- Generated DTOs, re-exported under their spec names ------------------------------------------
export type { UserDto } from './api/model/userDto';
export type { ConsumptionDto } from './api/model/consumptionDto';
export type { ConsumptionChangeDto } from './api/model/consumptionChangeDto';
export type { ConsumptionDeltaDto } from './api/model/consumptionDeltaDto';
export type { ConsumptionOverrideDto } from './api/model/consumptionOverrideDto';
export type { TokenRequestDto } from './api/model/tokenRequestDto';
export type { TokenResponseDto } from './api/model/tokenResponseDto';
export type { LedgerEntryDto } from './api/model/ledgerEntryDto';
export type { MemberSummaryDto } from './api/model/memberSummaryDto';
export type { PriceDto } from './api/model/priceDto';
export type { PriceChangeDto } from './api/model/priceChangeDto';
export type { ExpenseDto } from './api/model/expenseDto';
export type { PaymentDto } from './api/model/paymentDto';
export type { KittyDto } from './api/model/kittyDto';
export type { MemberBalanceDto } from './api/model/memberBalanceDto';

// --- Generated request DTOs, re-exported under the names the components already use --------------
// The spec names these `*Dto`; the frontend calls them `*Request`. The aliases keep both stable.
export type { MemberExpenseDto as MemberExpenseRequest } from './api/model/memberExpenseDto';
export type { AdminExpenseDto as AdminExpenseRequest } from './api/model/adminExpenseDto';
export type { PriceUpdateDto as PriceUpdateRequest } from './api/model/priceUpdateDto';
export type { SettlementRequestDto as SettlementRequest } from './api/model/settlementRequestDto';
export type { AdjustmentRequestDto as AdjustmentRequest } from './api/model/adjustmentRequestDto';
export type { ProfileUpdateDto as ProfileUpdateRequest } from './api/model/profileUpdateDto';

// --- Inline-enum unions surfaced as standalone aliases ------------------------------------------
// openapi-generator emits these enums namespaced on their owning DTO (`UserDto.RoleEnum`,
// `LedgerEntryDto.TypeEnum`); re-export the value-union types under the bare names the code uses.
import { UserDto } from './api/model/userDto';
import { LedgerEntryDto } from './api/model/ledgerEntryDto';

/** A user's role (`'USER' | 'ADMIN'`). */
export type Role = UserDto.RoleEnum;

/**
 * The kind of a unified-ledger row. The first four appear in a member's ledger; the last three (plus
 * `SETTLEMENT`) appear in the admin-only kitty ledger. All money is signed integer euro cents.
 */
export type LedgerEntryType = LedgerEntryDto.TypeEnum;
