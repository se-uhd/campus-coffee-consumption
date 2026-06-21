/** Mirrors the backend response/request DTOs. */

export type Role = 'USER' | 'ADMIN';

/** A user as returned/sent by the admin user endpoints. */
export interface UserDto {
  id?: string;
  createdAt?: string;
  updatedAt?: string;
  loginName: string;
  emailAddress: string;
  firstName: string;
  lastName: string;
  password?: string;
  role?: Role;
  active?: boolean;
  /** Read-only assembled "your coffee link" (the capability URL); never sent back. */
  capabilityUrl?: string;
}

/** One entry in a member's coffee change log, reconstructed from the event rows. */
export interface ConsumptionChangeDto {
  count: number;
  delta: number;
  createdAt: string;
  createdBy: string;
  note?: string;
}

/** A member's current total plus a page of recent changes (returned by every consumption endpoint). */
export interface ConsumptionDto {
  total: number;
  changes: ConsumptionChangeDto[];
}

/** Request body for a single-step +1/-1 change. */
export interface ConsumptionDeltaDto {
  delta: number;
}

/** Request body for an admin absolute override of a member's coffee total, with an optional note. */
export interface ConsumptionOverrideDto {
  total: number;
  note?: string;
}

/** Admin login request. */
export interface TokenRequestDto {
  loginName: string;
  password: string;
}

/** Admin login response carrying the JWT bearer token. */
export interface TokenResponseDto {
  token: string;
}

/**
 * The kind of a unified-ledger row. The first four appear in a member's ledger; the last three (plus
 * `SETTLEMENT`) appear in the admin-only kitty ledger. All money is signed integer euro cents.
 */
export type LedgerEntryType =
  | 'CONSUMPTION'
  | 'CONSUMPTION_CANCEL'
  | 'PRIVATE_EXPENSE'
  | 'SETTLEMENT'
  | 'KITTY_EXPENSE'
  | 'KITTY_ADJUSTMENT';

/**
 * One row of a unified ledger (a member's or the kitty's), reconstructed from a single event row.
 * `amountCents` is the signed effect on the running balance; `runningBalanceCents` is that balance after
 * the row. `count`/`delta` are present only for a consumption row, `weightGrams` only for an expense row.
 */
export interface LedgerEntryDto {
  type: LedgerEntryType;
  seq: number;
  createdAt: string;
  createdBy: string;
  note?: string;
  /** Signed effect on the balance, in euro cents. */
  amountCents: number;
  /** The balance after this entry, in euro cents. */
  runningBalanceCents: number;
  count?: number;
  delta?: number;
  weightGrams?: number;
}

/**
 * A member's landing page in one read: the coffee `count`, the price per cup, the member's balance
 * (negative ⇒ they owe the fund), the read-only communal kitty balance, whether their most recent coffee
 * is still cancellable within the grace period, and the first page of their unified ledger. Money is cents.
 */
export interface MemberSummaryDto {
  count: number;
  /** Current price per cup, in euro cents. */
  priceCents: number;
  /** The member's balance in euro cents (negative ⇒ they owe the fund, positive ⇒ in credit). */
  balanceCents: number;
  /** The communal kitty balance in euro cents (read-only for members). */
  kittyBalanceCents: number;
  /** Whether the member's most recent coffee can still be undone within the grace period. */
  cancellable: boolean;
  ledger: LedgerEntryDto[];
}

/** The current coffee price per cup, in euro cents. */
export interface PriceDto {
  amountCents: number;
}

/** One entry of the price history (newest first; the first entry is the current price). Money is cents. */
export interface PriceChangeDto {
  amountCents: number;
  createdAt: string;
  createdBy: string;
}

/** A recorded bean purchase; the kitty/private split always sums to `amountCents`. Money is euro cents. */
export interface ExpenseDto {
  id: string;
  buyerUserId: string;
  buyerLoginName: string;
  weightGrams: number;
  amountCents: number;
  privateAmountCents: number;
  kittyAmountCents: number;
  note?: string;
  createdAt?: string;
}

/** A kitty money movement: a settlement (with a member) or an adjustment (without). Money is euro cents. */
export interface PaymentDto {
  id: string;
  userId?: string;
  userLoginName?: string;
  amountCents: number;
  note?: string;
  createdAt?: string;
}

/** The communal kitty balance plus a page of its movements (newest first). Money is euro cents. */
export interface KittyDto {
  balanceCents: number;
  entries: LedgerEntryDto[];
}

/** One row of the admin balance overview: a member, their coffee count, and balance (cents). */
export interface MemberBalanceDto {
  userId: string;
  loginName: string;
  firstName: string;
  lastName: string;
  count: number;
  balanceCents: number;
}

/** Request body for a member recording their own bean purchase (booked 100% to their own pocket). */
export interface MemberExpenseRequest {
  weightGrams: number;
  amountCents: number;
  note?: string;
}

/** Request body for an admin recording/correcting a bean purchase for a member (with the kitty split). */
export interface AdminExpenseRequest {
  weightGrams: number;
  amountCents: number;
  privateAmountCents: number;
  kittyAmountCents: number;
  note?: string;
}

/** Request body for setting the global coffee price (euro cents). */
export interface PriceUpdateRequest {
  amountCents: number;
}

/** Request body for an admin recording that a member paid money into the kitty (a settlement). */
export interface SettlementRequest {
  userId: string;
  amountCents: number;
  note?: string;
}

/** Request body for an admin adjusting the kitty without a member (signed; may be negative). */
export interface AdjustmentRequest {
  amountCents: number;
  note?: string;
}
