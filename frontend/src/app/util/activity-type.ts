import { ActivityEntryType } from '../models';

/**
 * The display form of an activity actor's login: the automated `system` actor reads as `SYSTEM` (uppercased so
 * the non-user actor stands out from user and admin logins); every other login is shown unchanged.
 *
 * @param login the actor login (the event's `createdBy`)
 */
export function displayActor(login: string): string {
  return login === 'system' ? 'SYSTEM' : login;
}

/**
 * The Material icon name for an activity-entry type. Shared by the user/kitty activity list and the admin
 * global activity table so a row type renders the same glyph everywhere.
 *
 * @param type the activity-entry type
 * @returns the Material icon ligature name
 */
export function activityIcon(type: ActivityEntryType): string {
  switch (type) {
    case 'CONSUMPTION':
      return 'coffee';
    case 'CONSUMPTION_CANCEL':
      return 'undo';
    case 'PRIVATE_EXPENSE':
    case 'KITTY_EXPENSE':
      return 'shopping_cart';
    case 'DEPOSIT':
    case 'KITTY_ADJUSTMENT':
      return 'payments';
    case 'PRICE_CHANGE':
      return 'sell';
    case 'RATING':
      return 'grade';
    default:
      return 'receipt_long';
  }
}

/**
 * A human-readable label for an activity-entry type. Shared by the user/kitty activity list and the admin
 * global activity table so a row type reads the same everywhere.
 *
 * @param type the activity-entry type
 * @returns the display label
 */
export function activityLabel(type: ActivityEntryType): string {
  switch (type) {
    case 'CONSUMPTION':
      return 'Coffee cup';
    case 'CONSUMPTION_CANCEL':
      return 'Coffee canceled';
    case 'PRIVATE_EXPENSE':
      return 'Expense';
    case 'KITTY_EXPENSE':
      return 'Kitty expense';
    case 'DEPOSIT':
      return 'Deposit';
    case 'KITTY_ADJUSTMENT':
      return 'Kitty adjustment';
    case 'PRICE_CHANGE':
      return 'Price change';
    case 'RATING':
      return 'Rating';
    default:
      return 'Entry';
  }
}
