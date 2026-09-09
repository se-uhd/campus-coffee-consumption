import { Signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, NavigationEnd, Router } from '@angular/router';
import { filter, map } from 'rxjs';

/**
 * What a routed page contributes to the shared header: the centered title and the icon beside it. A landing
 * declares neither, which is what tells the shell to show the logo and its navigation instead of a back
 * arrow and a title.
 */
export interface PageChrome {
  /** The subpage title shown centered in the header; empty on a landing. */
  readonly title: string;

  /** The Material icon name shown before that title; empty for none. */
  readonly icon: string;
}

/** The chrome of a route that declares none: a landing. */
const NO_CHROME: PageChrome = { title: '', icon: '' };

/**
 * The chrome of the page currently activated below [route], as a signal the shell's header binds.
 *
 * The shell outlives its children, so it cannot read the child's data once at construction. It walks its own
 * snapshot down to the deepest activated child on every NavigationEnd instead, which is the moment the
 * router has finished swapping that child in.
 *
 * Call it from an injection context (a shell component's constructor or a field initializer): `toSignal`
 * needs one to tear the subscription down with the component.
 *
 * @param route the shell's own activated route
 * @param router the router whose navigations move the child below it
 * @returns the activated child's declared header title and icon
 */
export function pageChrome(route: ActivatedRoute, router: Router): Signal<PageChrome> {
  const read = (): PageChrome => {
    let snapshot = route.snapshot;
    while (snapshot.firstChild) {
      snapshot = snapshot.firstChild;
    }
    const data = snapshot.data as { headerTitle?: string; headerIcon?: string };
    return { title: data.headerTitle ?? NO_CHROME.title, icon: data.headerIcon ?? NO_CHROME.icon };
  };
  return toSignal(
    router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => read())
    ),
    { initialValue: read() }
  );
}
