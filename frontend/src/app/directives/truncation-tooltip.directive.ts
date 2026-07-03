import { Directive, DoCheck, ElementRef, inject, input } from '@angular/core';

/**
 * Shows a native browser tooltip (the `title` attribute, which the browser anchors at the cursor) with the
 * full text on hover, but only when the host element is actually truncated (its content overflows, as in an
 * ellipsized table cell). Apply with the full text, e.g.
 * `<span class="ellipsized" [ccTruncationTooltip]="value">{{ value }}</span>`. The overflow is re-checked on
 * every change-detection pass, so a column resize that changes whether the cell is clipped keeps the tooltip
 * in step; a cell that fits carries no `title` and shows no tooltip.
 */
@Directive({
  selector: '[ccTruncationTooltip]'
})
export class TruncationTooltipDirective implements DoCheck {
  /** The full text to reveal when the cell is truncated; an empty/absent value shows no tooltip. */
  readonly text = input<string | null | undefined>(null, { alias: 'ccTruncationTooltip' });

  private readonly host = inject<ElementRef<HTMLElement>>(ElementRef).nativeElement;

  ngDoCheck(): void {
    const value = this.text();
    // scrollWidth exceeds clientWidth exactly when the content is clipped by overflow: hidden
    if (value && this.host.scrollWidth > this.host.clientWidth) {
      this.host.title = value;
    } else {
      this.host.removeAttribute('title');
    }
  }
}
