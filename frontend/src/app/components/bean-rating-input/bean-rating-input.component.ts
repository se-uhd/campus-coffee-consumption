import { ChangeDetectionStrategy, Component, input, model, output } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatSelectModule } from '@angular/material/select';
import { CoffeeBeanDto } from '../../models';

/** A rating the user just cast: which bean they drank, and how many out of five it scored. */
export interface BeanRating {
  beanId: string;
  value: number;
}

/**
 * The landing's rating prompt: pick the beans that were just drunk from the shared catalog, then score them
 * one to five on a row of bean glyphs.
 *
 * It owns the selection and the scoring control and emits a complete rating, so the page it sits on decides
 * what casting a vote means (a user rates their own cup, an admin rates the viewed user's on their behalf).
 */
@Component({
  selector: 'cc-bean-rating-input',
  imports: [MatButtonModule, MatFormFieldModule, MatSelectModule],
  template: `
    <div class="cc-rating-card">
      <span class="cc-rating-label">Rate these beans</span>
      <mat-form-field class="cc-rating-bean" subscriptSizing="dynamic" appearance="outline">
        <mat-label>Beans</mat-label>
        <mat-select [value]="beanId()" (selectionChange)="beanId.set($event.value)" [disabled]="busy()">
          @for (bean of beans(); track bean.id) {
            <mat-option [value]="bean.id">{{ bean.name }}</mat-option>
          }
        </mat-select>
      </mat-form-field>
      <div class="cc-rating-beans" role="group" aria-label="Rating (one to five)">
        @for (position of positions; track position) {
          <button
            mat-icon-button
            type="button"
            (click)="rate(position)"
            [disabled]="busy() || !beanId()"
            [attr.aria-label]="position + ' out of 5'"
            [attr.aria-pressed]="(value() ?? 0) >= position"
          >
            <svg
              viewBox="0 0 24 24"
              class="cc-bean-svg"
              [class.cc-bean-filled]="(value() ?? 0) >= position"
              aria-hidden="true"
            >
              <path
                fill-rule="evenodd"
                clip-rule="evenodd"
                d="M12 2.5c3.9 0 6.5 4.6 6.5 9.5s-2.6 9.5-6.5 9.5S5.5 16.9 5.5 12 8.1 2.5 12 2.5Zm0 2.3c-1.7 2.5-1.7 12.4 0 14.9 1.7-2.5 1.7-12.4 0-14.9Z"
              />
            </svg>
          </button>
        }
      </div>
    </div>
  `,
  styles: [
    `
      .cc-rating-card {
        display: flex;
        flex-direction: column;
        align-items: center;
        gap: 10px;
        margin-top: 20px;
        padding: 14px 18px 10px;
        border: 1px solid rgba(200, 16, 46, 0.2);
        border-radius: 16px;
        background: rgba(200, 16, 46, 0.05);
      }

      .cc-rating-label {
        color: var(--cc-ink);
        font-weight: 600;
      }

      .cc-rating-bean {
        width: 240px;
      }

      /* neutralize the global in-card field rhythm here so the flex gap centers the dropdown evenly between
         the label and the bean scale (the global rule adds a 12px bottom margin that skews it lower) */
      .cc-rating-card mat-form-field {
        margin-bottom: 0;
      }

      /* the icon buttons are ~40px tall around a 26px bean, so their intrinsic top padding pushes the visible
         scale down; pull the row up to cancel it, so the dropdown sits evenly between the label and the scale */
      .cc-rating-beans {
        display: inline-flex;
        align-items: center;
        gap: 2px;
        margin-top: -8px;
      }

      /* block, so the button's flex centers the bean instead of the icon sitting a few px below the button's
         optical center (an inline SVG rides the text baseline) */
      .cc-bean-svg {
        display: block;
        width: 26px;
        height: 26px;
        fill: rgba(0, 0, 0, 0.26);
        transition: fill 0.15s ease;
      }

      .cc-bean-svg.cc-bean-filled {
        fill: var(--cc-primary, #c8102e);
      }
    `
  ],
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class BeanRatingInputComponent {
  /** The selectable catalog beans to choose from. */
  readonly beans = input<CoffeeBeanDto[]>([]);

  /** The score already recorded in this window, which fills that many glyphs; null when nothing is voted. */
  readonly value = input<number | null>(null);

  /** Whether a request is in flight, which disables the control. */
  readonly busy = input(false);

  /** Emitted when a score is picked for a chosen bean; the host records it. */
  readonly rated = output<BeanRating>();

  /**
   * The chosen bean. Two-way, because the summary names a bean to pre-select (the one just drunk) and the
   * page clears it when that bean is not among the options.
   */
  readonly beanId = model('');

  readonly positions = [1, 2, 3, 4, 5];

  /**
   * Emits the picked score for the selected bean.
   *
   * @param value the score, one to five
   */
  rate(value: number): void {
    const beanId = this.beanId();
    if (this.busy() || !beanId) {
      return;
    }
    this.rated.emit({ beanId, value });
  }
}
