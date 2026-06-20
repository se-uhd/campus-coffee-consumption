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

/** Request body for an admin absolute override (a total of 0 is a reset after payment). */
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
