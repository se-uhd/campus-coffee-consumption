import { describe, it, expect, beforeEach, vi, type Mock } from 'vitest';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideZonelessChangeDetection } from '@angular/core';
import { provideRouter } from '@angular/router';
import { HttpErrorResponse } from '@angular/common/http';
import { AdminPriceComponent } from './admin-price.component';
import { NotificationService } from '../../services/notification.service';
import { PageLoadingService } from '../../services/page-loading.service';
import { PriceService } from '../../services/price.service';
import { PriceChangeDto } from '../../models';

function priceChange(amountCents: number): PriceChangeDto {
  return {
    amountCents,
    createdAt: '2026-09-09T08:00:00Z',
    createdBy: 'jane_doe'
  };
}

/**
 * The price page sets the one number every cup is valued at, so its tests are about what reaches the server:
 * integer cents parsed from a typed euro string, nothing at all when that string is not a price, and a field
 * that is only cleared once the change has actually been stored.
 */
describe('AdminPriceComponent', () => {
  let fixture: ComponentFixture<AdminPriceComponent>;
  let component: AdminPriceComponent;
  let history: Mock;
  let setPrice: Mock;
  let track: Mock;
  let notifySuccess: Mock;
  let notifyError: Mock;

  /** Creates the page holding the history the resolver read, and lets its first render settle. */
  async function build(entries: PriceChangeDto[] | null): Promise<void> {
    fixture = TestBed.createComponent(AdminPriceComponent);
    component = fixture.componentInstance;
    fixture.componentRef.setInput('priceHistory', { value: entries });
    await fixture.whenStable();
  }

  beforeEach(() => {
    history = vi.fn().mockResolvedValue([priceChange(50)]);
    setPrice = vi.fn().mockResolvedValue(undefined);
    track = vi.fn((work: () => Promise<void>) => work());
    notifySuccess = vi.fn();
    notifyError = vi.fn();

    TestBed.configureTestingModule({
      providers: [
        provideZonelessChangeDetection(),
        provideRouter([]),
        { provide: PriceService, useValue: { history, setPrice } },
        { provide: NotificationService, useValue: { success: notifySuccess, error: notifyError } },
        { provide: PageLoadingService, useValue: { track } }
      ]
    });
  });

  it('reads the current price from the newest history entry, and zero when none has been set', async () => {
    // The history is newest first, so the current price is its head. Reading the tail would show the price
    // the fund charged when it opened.
    await build([priceChange(60), priceChange(50)]);
    expect(component.currentPriceCents()).toBe(60);

    fixture.componentRef.setInput('priceHistory', { value: [] });
    await fixture.whenStable();

    expect(component.currentPriceCents(), 'an empty history is no price, not a wrong one').toBe(0);
  });

  it('reports a failed history load as a retryable error and an empty history as no error', async () => {
    await build(null);
    expect(component.loadError()).toBe('Could not load the price history.');

    fixture.componentRef.setInput('priceHistory', { value: [] });
    await fixture.whenStable();

    expect(component.loadError(), 'a fund with no price yet has loaded fine').toBe('');
  });

  // Money is integer cents end to end: a float would round the fund's arithmetic in ways nobody can audit.
  // Either separator is accepted because both are typed here, and zero is a price (free coffee), not junk.
  it.each([
    ['0.55', 55],
    ['0,55', 55],
    ['0', 0]
  ])('sends a typed price of %s to the server as %i cents', async (typed, cents) => {
    await build([priceChange(50)]);
    component.newPriceEuros = typed;

    await component.save();

    expect(setPrice).toHaveBeenCalledWith(cents);
  });

  it('sends nothing and says why when the typed price is not a price', async () => {
    // The field is free text. Sending NaN or a silently coerced zero would set the price of every future cup
    // to nothing.
    await build([priceChange(50)]);

    for (const typed of ['', 'abc', '0.555', '1.2.3', '-0.50']) {
      component.newPriceEuros = typed;
      await component.save();
    }

    expect(setPrice, 'no unparseable or negative amount may reach the server').not.toHaveBeenCalled();
    expect(notifyError).toHaveBeenCalledWith(null, 'Enter a valid, non-negative price (e.g. 0.50).');
  });

  it('clears the field and reloads the history once the price has been stored', async () => {
    await build([priceChange(50)]);
    history.mockResolvedValue([priceChange(55), priceChange(50)]);
    component.newPriceEuros = '0.55';

    await component.save();

    expect(component.newPriceEuros, 'a stored price must not stay in the field to be sent twice').toBe('');
    expect(component.currentPriceCents()).toBe(55);
    expect(notifySuccess).toHaveBeenCalledWith('Price updated.');
  });

  it('keeps the typed price in the field when the server refuses it', async () => {
    // The admin has to be able to correct or resend it, which they cannot do if the page threw it away.
    await build([priceChange(50)]);
    setPrice.mockRejectedValue(new HttpErrorResponse({ status: 409 }));
    component.newPriceEuros = '0.55';

    await component.save();

    expect(component.newPriceEuros).toBe('0.55');
    expect(notifyError).toHaveBeenCalledWith(expect.anything(), 'Could not set the price.');
    expect(component.busy()).toBe(false);
  });

  it('sends one price change when Save is tapped twice in a row', async () => {
    await build([priceChange(50)]);
    let release = (): void => undefined;
    setPrice.mockReturnValue(
      new Promise<void>((resolve) => {
        release = resolve;
      })
    );
    component.newPriceEuros = '0.55';

    const first = component.save();
    const second = component.save();
    release();
    await Promise.all([first, second]);

    expect(setPrice).toHaveBeenCalledTimes(1);
  });

  it('flags a price typed with both separators before it is ever sent', async () => {
    await build([priceChange(50)]);
    component.newPriceEuros = '1.234,56';

    expect(component.priceError()).toBe('Use either a comma or a point as the decimal separator, not both.');
  });

  it('raises the app loading indicator for a Retry and not for the reload it wraps', async () => {
    await build(null);

    await component.retry();
    expect(track).toHaveBeenCalledTimes(1);
    expect(history, 'the Retry has to actually re-read, not just raise the bar').toHaveBeenCalledTimes(1);

    await component.refresh();

    expect(track).toHaveBeenCalledTimes(1);
  });

  it('reports a stored price as stored even when the reload that follows it fails', async () => {
    // The write has committed. Reporting it as a failed save would send the admin to set the same price
    // again, and the second attempt would look like it worked when the first already had.
    await build([priceChange(50)]);
    history.mockRejectedValue(new HttpErrorResponse({ status: 500 }));
    component.newPriceEuros = '0.55';

    await component.save();

    expect(setPrice).toHaveBeenCalledWith(55);
    expect(notifySuccess).toHaveBeenCalledWith('Price updated.');
    expect(notifyError, 'a failed reload is not a failed save').not.toHaveBeenCalled();
    expect(component.loadError(), 'the stale history is what the error card is for').toBe(
      'Could not load the price history.'
    );
  });
});
