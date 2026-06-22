import { Component, ChangeDetectionStrategy } from '@angular/core';
import { RouterOutlet } from '@angular/router';

/** Root component: a router outlet shell. Each page renders its own header. */
@Component({
  selector: 'cc-root',
  imports: [RouterOutlet],
  changeDetection: ChangeDetectionStrategy.Eager,
  template: '<router-outlet></router-outlet>'
})
export class AppComponent {}
