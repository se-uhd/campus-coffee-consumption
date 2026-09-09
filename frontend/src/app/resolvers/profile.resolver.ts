import { inject } from '@angular/core';
import { ResolveFn } from '@angular/router';
import { ProfileService } from '../services/profile.service';
import { UserService } from '../services/user.service';
import { UserDto } from '../models';
import { Preload, preload } from '../util/preload';
import { adminSubject } from './admin-subject';

/** Everything the profile page needs to render its first frame complete. */
export interface ProfilePageData {
  /** The profile being shown. */
  readonly profile: UserDto;

  /** That user's capability QR code, fetched as a blob so the request carries the credential. */
  readonly qr: Blob;

  /** The user the page is about; empty in user mode, where the capability token names them. */
  readonly subjectId: string;
}

/**
 * Preloads the user's own profile. The details and the QR are fetched together rather than one after the
 * other, so the card no longer waits on an image download before it can render.
 */
export const userProfileResolver: ResolveFn<Preload<ProfilePageData>> = () => {
  const profileService = inject(ProfileService);

  return preload(
    (async (): Promise<ProfilePageData> => {
      const [profile, qr] = await Promise.all([profileService.get(), profileService.qrBlob()]);
      return { profile, qr, subjectId: '' };
    })()
  );
};

/** Preloads the selected user's profile and QR for an admin, in parallel once the subject is known. */
export const adminProfileResolver: ResolveFn<Preload<ProfilePageData>> = (route) => {
  const userService = inject(UserService);
  // started synchronously, inside the resolver's injection context
  const subject = adminSubject(route);

  return preload(
    (async (): Promise<ProfilePageData | null> => {
      const subjectId = await subject;
      if (!subjectId) {
        return null;
      }
      const [profile, qr] = await Promise.all([userService.get(subjectId), userService.qrBlob(subjectId)]);
      return { profile, qr, subjectId };
    })()
  );
};
