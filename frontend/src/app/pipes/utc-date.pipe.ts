import { Pipe, PipeTransform } from '@angular/core';

/**
 * Normalizes a server timestamp to UTC for display. The backend serializes timestamps as a `LocalDateTime`
 * with no timezone offset (e.g. `2026-06-21T08:30:00`), but the value is UTC. Angular's `DatePipe` would
 * otherwise parse a no-offset string as browser-local time and render it ~1-2h off. This pipe appends a `Z`
 * (when the string carries no offset already) so the downstream `DatePipe` interprets it as UTC and converts
 * it to the viewer's local zone correctly. Pass its result straight into `DatePipe`, e.g.
 * `{{ entry.createdAt | utcDate | date: 'short' }}`.
 */
@Pipe({ name: 'utcDate' })
export class UtcDatePipe implements PipeTransform {
  /**
   * Tags a no-offset server timestamp as UTC.
   *
   * @param value the server timestamp string (a `LocalDateTime` without an offset), or null/undefined
   * @returns the same instant marked as UTC (a trailing `Z`), or the value unchanged when it is empty or
   *   already carries a timezone offset
   */
  transform(value: string | null | undefined): string | null | undefined {
    if (value == null || value === '') {
      return value;
    }
    // leave it alone if it already has a zone (a trailing Z or a ±hh:mm offset on the time part)
    if (/[zZ]$/.test(value) || /\d{2}:\d{2}[+-]\d{2}:?\d{2}$/.test(value)) {
      return value;
    }
    return `${value}Z`;
  }
}
