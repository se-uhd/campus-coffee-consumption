import { activityIcon, activityLabel } from './activity-type';

describe('activity-type', () => {
  it('maps a price change to its own icon and label', () => {
    expect(activityIcon('PRICE_CHANGE')).toBe('sell');
    expect(activityLabel('PRICE_CHANGE')).toBe('Price change');
  });

  it('maps coffees, expenses, and deposits to their shared glyphs and labels', () => {
    expect(activityIcon('CONSUMPTION')).toBe('coffee');
    expect(activityIcon('CONSUMPTION_CANCEL')).toBe('undo');
    expect(activityIcon('PRIVATE_EXPENSE')).toBe('shopping_cart');
    expect(activityIcon('KITTY_EXPENSE')).toBe('shopping_cart');
    expect(activityIcon('DEPOSIT')).toBe('payments');
    expect(activityLabel('PRIVATE_EXPENSE')).toBe('Expense');
    expect(activityLabel('KITTY_ADJUSTMENT')).toBe('Kitty adjustment');
  });
});
