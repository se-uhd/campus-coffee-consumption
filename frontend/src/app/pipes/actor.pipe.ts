import { Pipe, PipeTransform } from '@angular/core';
import { displayActor } from '../util/activity-type';

/**
 * Renders an activity actor's login for display: the automated `system` actor reads as `SYSTEM`, every other
 * login unchanged. The transform itself lives in {@link displayActor} so non-template code can produce the
 * identical string; this pipe is the single template-side delegate (used as `value | actor`), so no page
 * repeats the mapping.
 */
@Pipe({ name: 'actor' })
export class ActorPipe implements PipeTransform {
  /**
   * Renders an actor login for display.
   *
   * @param login the actor login (the event's `createdBy`)
   * @returns the display form (`SYSTEM` for the automated actor, otherwise the login unchanged)
   */
  transform(login: string): string {
    return displayActor(login);
  }
}
