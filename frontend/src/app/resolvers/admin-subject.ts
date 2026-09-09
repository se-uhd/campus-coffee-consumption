import { inject } from '@angular/core';
import { ActivatedRouteSnapshot } from '@angular/router';
import { AdminSelectionService } from '../services/admin-selection.service';

/**
 * The user an admin page is about: the `?user=` query param, or the admin's own account when there is none.
 *
 * The fallback needs the admin's own id, which arrives with the user directory, so this waits for the
 * directory before answering. With a `?user=` a warm cache answers in a microtask; without one it waits for
 * a current read, because the fallback is the acting admin's identity and a cached id can belong to an admin
 * who signed out in another tab. It cannot be split into a separate resolve key: the resolve keys of one
 * route all start together, so a sibling key could not be relied on to have loaded the directory first.
 *
 * @param selection the shared admin selection, which owns the directory and the own-account id
 * @param param the `?user=` value, or null when it is absent
 * @returns the subject's user id, or an empty string when the directory could not be read
 */
export async function resolveAdminSubject(
  selection: AdminSelectionService,
  param: string | null
): Promise<string> {
  try {
    // With a `?user=` the URL names the subject outright, so a cached directory is enough. Without one the
    // subject IS the signed-in admin's own account, so a stale id would silently open somebody else's page:
    // wait for a current one instead of answering from the cache.
    await (param ? selection.ensureLoaded() : selection.ensureIdentityFresh());
  } catch {
    return '';
  }
  return selection.selectFromParam(param);
}

/**
 * {@link resolveAdminSubject} for a route resolver. It injects synchronously, before it awaits anything, so
 * it stays inside the resolver's injection context; an `inject()` after an `await` throws and would take
 * the whole navigation down with it.
 *
 * @param route the activated route whose `?user=` names the subject
 * @returns the subject's user id, or an empty string when the directory could not be read
 */
export function adminSubject(route: ActivatedRouteSnapshot): Promise<string> {
  return resolveAdminSubject(inject(AdminSelectionService), route.queryParamMap.get('user'));
}
