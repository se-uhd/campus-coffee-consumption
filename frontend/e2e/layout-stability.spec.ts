import { APIRequestContext, Page, Request, Route, TestInfo } from '@playwright/test';
import pixelmatch from 'pixelmatch';
import { PNG } from 'pngjs';
import { expect, test } from './fixtures';
import {
  ADMIN,
  adminToken,
  apiContext,
  loginAsAdmin,
  pinPrice,
  resetFixtures,
  TOTP_CODE,
  USER_TOKENS
} from './helpers';

/**
 * The layout-stability gate: after a page transition, the destination must not change once it is on screen.
 *
 * WHY A SETTLE DIFF AND NOT CUMULATIVE LAYOUT SHIFT. A 4px progress bar in an ~800px viewport scores below
 * any sane CLS threshold, and CLS is blind both to a route transition and to growth at the bottom of the
 * document, so a CLS gate reads green on code that visibly flickers. The complaint is "the page changes
 * after it appears", which is a mutation question: capture the destination at its first painted frame,
 * capture it again once everything it asked for has landed, and require the two to be identical. Layout-shift
 * entries are still recorded and reported (count and raw pixels, never filtered on `hadRecentInput`, which
 * excludes anything within ~500ms of a click), but they gate nothing.
 *
 * HOW THE FIRST FRAME IS CAPTURED: HOLD, DO NOT DELAY. A harness cannot photograph "the first frame": a
 * locator wait retries with backoff and `Page.captureScreenshot` composites a fresh frame after a round trip,
 * so against a localhost backend the data usually lands before capture 1 and the diff reads green by luck.
 * Instead every `/api/**` request is routed through a gate that decides, per request, whether the request
 * belongs to the page being left (or to a route resolver, which runs before the new page exists) or to the
 * page that has just been activated. A request from the page being left continues at once. A request from
 * the new page is parked until capture 1 has been taken.
 *
 * SOUNDNESS OF THE GATE'S PROBE. Before each trigger the spec stamps the outgoing page's `.page` element with
 * `data-pw-prev`. The probe is then "does an unstamped `.page` exist?", and a request is parked exactly when
 * it is true. The router updates the URL and activates the route tree in one synchronous step after every
 * guard and resolver has settled, and `ngOnInit` runs inside that same task's change detection, so a request
 * the new page issues itself always observes the new (unstamped) `.page`, and a resolver's request never can.
 * For a same-route re-resolve (an admin switching `?user=`, where the `.page` element is reused) that probe
 * cannot distinguish the two, so those transitions use `location.href === <target>` instead and are kept
 * forward-only in the walk.
 *
 * TEST GRANULARITY. One test per destination page, covering the transition to it and the transition back, so
 * a failure still names one page and one direction. A whole walk in a single test does not fit the 30s
 * timeout, and the suite runs single-worker.
 */

/**
 * The height in CSS pixels of the strip at the very top of the viewport that both captures exclude. The
 * app's navigation progress bar is `position: fixed` at `inset: 0 0 auto 0` and is Material's default 4px
 * tall, and it may legitimately be sweeping in capture 1 and gone in capture 2. `mask` cannot express that
 * (it paints only where the masked element exists at capture time, so it would blank the strip in one
 * capture and not the other). Nothing else lives in the strip: the header bar starts below it, so an
 * in-flow progress bar between the header and the content is still compared.
 */
const NAV_BAR_STRIP_PX = 4;

/**
 * What the gate parks on. `new-page` is the normal transition: an activated page whose `.page` element
 * carries no outgoing stamp. `url` is the same-route re-resolve, where the `.page` element is reused and
 * only the URL distinguishes the two.
 */
type ProbeSpec = { readonly kind: 'new-page' } | { readonly kind: 'url'; readonly href: string };

/** The probe for a normal transition. */
const NEW_PAGE_PROBE: ProbeSpec = { kind: 'new-page' };

/**
 * The parking condition, evaluated in the page. It is a function, never a string expression: Playwright
 * evaluates a string predicate with `eval` inside the page, which the app's `script-src 'self'` CSP refuses.
 *
 * @param spec which condition to apply
 */
const probe = (spec: ProbeSpec): boolean =>
  spec.kind === 'url' ? location.href === spec.href : !!document.querySelector('.page:not([data-pw-prev])');

/**
 * How many release-and-observe rounds the drain runs before giving up. A page with a serial request
 * waterfall parks a second wave once the first resolves (the profile page fetches the user, then its QR),
 * and capturing before that wave lands would read green on exactly the flicker this gate exists to catch.
 * Six rounds is far past any chain the app has; exceeding it fails the test rather than capturing early.
 */
const MAX_DRAIN_ROUNDS = 6;

/** The two viewports every transition is walked at: a desktop column and a narrow phone. */
const VIEWPORTS = [
  { label: 'desktop', viewport: { width: 1280, height: 800 } },
  { label: 'mobile', viewport: { width: 390, height: 844 } }
] as const;

/** The gate's mutable state: whether it is arming, which probe it applies, and what it is holding. */
interface Hold {
  armed: boolean;
  spec: ProbeSpec;
  parked: Route[];
}

/** A viewport-sized screenshot clip in document coordinates, minus the progress-bar strip at the top. */
interface Clip {
  x: number;
  y: number;
  width: number;
  height: number;
}

/**
 * Installs the request gate on a page. It runs once per test; `hold.armed` turns it on for the duration of
 * one transition. A probe that cannot be evaluated (the page is mid-document-swap) defaults to continuing
 * the request, so the gate can never stall a page.
 *
 * @param page the Playwright page to gate
 * @param hold the gate state the transition helper drives
 */
async function installHold(page: Page, hold: Hold): Promise<void> {
  await page.route('**/api/**', async (route) => {
    if (!hold.armed) {
      await route.continue();
      return;
    }
    let park = false;
    try {
      park = await page.evaluate(probe, hold.spec);
    } catch {
      park = false;
    }
    if (park) {
      hold.parked.push(route);
    } else {
      await route.continue();
    }
  });
}

/** Marks the currently-rendered page as the one being left, so the gate can recognize its successor. */
async function stampCurrentPage(page: Page): Promise<void> {
  await page.evaluate(() => document.querySelector('.page')?.setAttribute('data-pw-prev', ''));
}

/** Resolves once the page has rendered two animation frames, i.e. at least one paint has happened. */
async function settleFrames(page: Page): Promise<void> {
  await page.evaluate(
    () =>
      new Promise<void>((resolve) => {
        requestAnimationFrame(() => requestAnimationFrame(() => resolve()));
      })
  );
}

/**
 * Awaits the decode of every image the page loads eagerly, so capture 1 is not taken between an image's
 * layout box appearing and its pixels arriving. Lazy images are skipped: they are not part of the first
 * frame by definition. Fonts need no equivalent; Playwright waits for them before screenshotting.
 *
 * @param page the Playwright page
 */
async function awaitEagerImageDecodes(page: Page): Promise<void> {
  await page.evaluate(async () => {
    await Promise.all(
      [...document.images]
        .filter((img) => img.loading !== 'lazy')
        .map((img) => img.decode().catch(() => undefined))
    );
  });
}

/** The current scroll offset, recorded at both captures so a late scroll is reported rather than hidden. */
async function scrollOffset(page: Page): Promise<{ x: number; y: number }> {
  return page.evaluate(() => ({ x: window.scrollX, y: window.scrollY }));
}

/**
 * The clip both captures share: the viewport minus the progress-bar strip at its top. It is pinned to the
 * top of the document, which is where every transition in this walk lands (the router restores the scroll
 * position, and a fresh page starts at the top); `expectSettled` asserts that, so the clip cannot silently
 * frame the wrong region.
 *
 * @param page the Playwright page
 */
function viewportClip(page: Page): Clip {
  const size = page.viewportSize()!;
  return {
    x: 0,
    y: NAV_BAR_STRIP_PX,
    width: size.width,
    height: size.height - NAV_BAR_STRIP_PX
  };
}

/** Takes one comparison screenshot: animations frozen and the text caret hidden, so only content differs. */
async function capture(page: Page, clip: Clip): Promise<Buffer> {
  return page.screenshot({ clip, animations: 'disabled', caret: 'hide' });
}

/**
 * Releases a batch of parked requests and resolves once every one of them has finished (or failed), so the
 * caller knows the responses have reached the browser rather than merely having been sent.
 *
 * @param page the Playwright page whose network events are observed
 * @param parked the routes to release
 */
async function releaseAndAwait(page: Page, parked: Route[]): Promise<void> {
  const pending = new Set<Request>(parked.map((route) => route.request()));
  const finished = new Promise<void>((resolve) => {
    const settle = (request: Request): void => {
      if (!pending.delete(request)) {
        return;
      }
      if (pending.size === 0) {
        page.off('requestfinished', settle);
        page.off('requestfailed', settle);
        resolve();
      }
    };
    page.on('requestfinished', settle);
    page.on('requestfailed', settle);
  });
  await Promise.all(parked.map((route) => route.continue().catch(() => undefined)));
  await finished;
}

/**
 * Lets the destination page finish everything it asked for: release what is parked, give it a frame to
 * render and to issue any follow-up request, and repeat until it parks nothing more.
 *
 * @param page the Playwright page
 * @param hold the gate state holding the parked requests
 */
async function drain(page: Page, hold: Hold): Promise<void> {
  for (let round = 0; round < MAX_DRAIN_ROUNDS; round++) {
    const parked = hold.parked.splice(0);
    if (parked.length === 0) {
      await settleFrames(page);
      return;
    }
    await releaseAndAwait(page, parked);
    await settleFrames(page);
  }
  throw new Error(`the page kept issuing requests for ${MAX_DRAIN_ROUNDS} rounds; it never settled`);
}

/**
 * Marks the header currently on screen, so the next transition can tell whether the shell kept it or rebuilt
 * it. A shell owns one header for the lifetime of its audience's pages, so the mark survives every
 * navigation inside that shell and is gone the moment the walk crosses into another one.
 *
 * @param page the Playwright page
 */
async function stampHeader(page: Page): Promise<void> {
  await page.evaluate(() => document.querySelector('cc-app-header')?.setAttribute('data-pw-header', ''));
}

/** Whether the header on screen is the same element that was there before the transition. */
async function headerSurvived(page: Page): Promise<boolean> {
  return page.evaluate(() => !!document.querySelector('cc-app-header[data-pw-header]'));
}

/** Installs the layout-shift recorder before any app code runs, so no entry is missed on a cold load. */
async function recordLayoutShifts(page: Page): Promise<void> {
  await page.addInitScript(() => {
    const shifts: { value: number; hadRecentInput: boolean }[] = [];
    (window as unknown as { __ccShifts: typeof shifts }).__ccShifts = shifts;
    new PerformanceObserver((list) => {
      for (const entry of list.getEntries()) {
        const shift = entry as PerformanceEntry & { value: number; hadRecentInput: boolean };
        shifts.push({ value: shift.value, hadRecentInput: shift.hadRecentInput });
      }
    }).observe({ type: 'layout-shift', buffered: true });
  });
}

/** The layout-shift entries recorded so far (never filtered: an input-adjacent shift still moved the page). */
async function layoutShifts(page: Page): Promise<{ value: number; hadRecentInput: boolean }[]> {
  return page.evaluate(
    () =>
      (window as unknown as { __ccShifts?: { value: number; hadRecentInput: boolean }[] }).__ccShifts ?? []
  );
}

/**
 * Attaches both captures, and a pixel diff of them, under `test-results/` so a red run can be triaged
 * without re-running it. A size change between the captures is itself the finding, and leaves no diff to
 * compute.
 *
 * @param testInfo the running test, which owns the attachment directory
 * @param name the transition name, used as the attachment prefix
 * @param first the capture taken at the destination's first painted frame
 * @param second the capture taken once the destination had everything it asked for
 */
async function attachCaptures(
  testInfo: TestInfo,
  name: string,
  first: Buffer,
  second: Buffer
): Promise<void> {
  await testInfo.attach(`${name} 1 first frame`, { body: first, contentType: 'image/png' });
  await testInfo.attach(`${name} 2 after settle`, { body: second, contentType: 'image/png' });
  const a = PNG.sync.read(first);
  const b = PNG.sync.read(second);
  if (a.width !== b.width || a.height !== b.height) {
    return;
  }
  const diff = new PNG({ width: a.width, height: a.height });
  pixelmatch(a.data, b.data, diff.data, a.width, a.height, { threshold: 0 });
  await testInfo.attach(`${name} 3 diff`, { body: PNG.sync.write(diff), contentType: 'image/png' });
}

/**
 * Runs one transition under the gate and asserts the destination does not change after it appears.
 *
 * @param page the Playwright page
 * @param hold the gate state installed on that page
 * @param testInfo the running test, for the failure attachments
 * @param name the transition's name, e.g. `admin landing -> price`
 * @param trigger the interaction that starts the transition (a click, a `goto`, a form submit)
 * @param spec the gate's parking condition; defaults to "an unstamped `.page` exists"
 * @param header whether the shell is expected to keep its header across this transition, or to rebuild it
 *   because the walk crosses from one audience's shell into another
 */
async function expectSettled(
  page: Page,
  hold: Hold,
  testInfo: TestInfo,
  name: string,
  trigger: () => Promise<void>,
  spec: ProbeSpec = NEW_PAGE_PROBE,
  header: 'persists' | 'rebuilt' = 'persists'
): Promise<void> {
  await stampCurrentPage(page);
  await stampHeader(page);
  const shiftsBefore = (await layoutShifts(page)).length;
  hold.spec = spec;
  hold.parked = [];
  hold.armed = true;
  try {
    await trigger();
    await page.waitForFunction(probe, spec, { polling: 'raf' });
    await awaitEagerImageDecodes(page);

    const clip = viewportClip(page);
    const first = await capture(page, clip);
    const scrollAtFirst = await scrollOffset(page);

    await drain(page, hold);

    const second = await capture(page, clip);
    const scrollAtSecond = await scrollOffset(page);

    const shifts = (await layoutShifts(page)).slice(shiftsBefore);
    if (shifts.length > 0) {
      const total = shifts.reduce((sum, shift) => sum + shift.value, 0);
      await testInfo.attach(`${name} layout-shift entries`, {
        body: `${shifts.length} entries, total score ${total.toFixed(4)}\n${JSON.stringify(shifts, null, 2)}`,
        contentType: 'text/plain'
      });
    }

    if (!first.equals(second)) {
      await attachCaptures(testInfo, name, first, second);
    }

    expect
      .soft(
        await headerSurvived(page),
        `${name}: the header should ${header === 'persists' ? 'survive' : 'be rebuilt'} across this transition`
      )
      .toBe(header === 'persists');
    // Soft, so a walk with several transitions reports every one of them rather than stopping at the
    // first: the page is left in a settled state either way, so the rest of the walk is still valid, and
    // a red run then names every transition that moved instead of only the earliest.
    expect
      .soft(
        { first: scrollAtFirst, second: scrollAtSecond },
        `${name}: the destination must arrive at the top of the document and stay there (the capture clip is pinned there)`
      )
      .toEqual({ first: { x: 0, y: 0 }, second: { x: 0, y: 0 } });
    expect
      .soft(first.equals(second), `${name}: the page changed after it appeared (see the attached captures)`)
      .toBe(true);
  } finally {
    hold.armed = false;
    const leftover = hold.parked.splice(0);
    await Promise.all(leftover.map((route) => route.continue().catch(() => undefined)));
  }
}

/**
 * Clicks a navigating control and parks the pointer at the origin afterwards. A history return (or any
 * transition that puts a control back under a resting cursor) makes Chromium re-apply `:hover` and open the
 * tooltip, which would land in one capture and not the other.
 *
 * @param page the Playwright page
 * @param click the click to perform
 */
async function clickAndPark(page: Page, click: () => Promise<void>): Promise<void> {
  await click();
  await page.mouse.move(0, 0);
}

/** The header's back arrow, the in-app way back from every subpage to its landing. */
function backArrow(page: Page) {
  return page.getByRole('link', { name: 'Back' });
}

/**
 * Seeds a small, deterministic dataset over the freshly-reset fixtures, so every page in the walk renders
 * populated. Without it the reset leaves the kitty at zero, every list empty and every balance zero, and a
 * page whose placeholder happens to equal its loaded state (a zero kitty balance, an empty history) reads
 * green while still flickering against real data. The admin's own account carries most of it, because it is
 * the default subject of every admin page.
 *
 * @param api a Playwright request context bound to the app base URL
 */
async function seedWalkData(api: APIRequestContext): Promise<void> {
  const token = await adminToken(api);
  const headers = { Authorization: `Bearer ${token}` };
  const expectOk = async (what: string, call: Promise<{ ok: () => boolean; status: () => number }>) => {
    const response = await call;
    expect(response.ok(), `seeding ${what} should succeed, got ${response.status()}`).toBeTruthy();
    return response;
  };

  // fund the kitty first: an expense with a kitty share is refused while the kitty would go negative
  await expectOk(
    'the kitty float',
    api.post('/api/kitty/adjustment', { headers, data: { amountCents: 5000, note: 'Layout walk float' } })
  );

  const me = (await (await expectOk('the admin lookup', api.get('/api/users/me', { headers }))).json()) as {
    id: string;
  };
  for (let i = 0; i < 2; i++) {
    await expectOk(
      'an admin coffee',
      api.post(`/api/users/${me.id}/consumption`, { headers, data: { delta: 1 } })
    );
  }
  await expectOk(
    'an admin purchase',
    api.post(`/api/users/${me.id}/expenses`, {
      headers,
      data: {
        expenseType: 'BEANS',
        beanName: 'Layout Roast',
        weightGrams: 500,
        amountCents: 900,
        privateAmountCents: 600,
        kittyAmountCents: 300,
        note: 'Layout walk purchase'
      }
    })
  );

  const userHeaders = { 'X-Capability-Token': USER_TOKENS.maxmustermann };
  await expectOk('a user coffee', api.post('/api/consumption', { headers: userHeaders, data: {} }));
  const summary = (await (
    await expectOk('the user summary', api.get('/api/summary', { headers: userHeaders }))
  ).json()) as { id?: string; ratingPrompt?: { defaultBeanId?: string } };
  const beanId = summary.ratingPrompt?.defaultBeanId;
  if (beanId) {
    await expectOk(
      'a user rating',
      api.put('/api/consumption/rating', { headers: userHeaders, data: { beanId, value: 4 } })
    );
  }

  const users = (await (await expectOk('the user list', api.get('/api/users', { headers }))).json()) as {
    id: string;
    loginName: string;
  }[];
  const max = users.find((user) => user.loginName === 'maxmustermann')!;
  await expectOk(
    'a deposit',
    api.post('/api/kitty/deposit', {
      headers,
      data: { userId: max.id, amountCents: 1000, note: 'Layout walk deposit' }
    })
  );
}

test.describe('layout stability', () => {
  let api: APIRequestContext;

  test.beforeAll(async () => {
    api = await apiContext();
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  for (const { label, viewport } of VIEWPORTS) {
    test.describe(label, () => {
      test.use({ viewport });

      let hold: Hold;

      test.beforeEach(async ({ page }) => {
        await resetFixtures(api);
        // pin the price so a leftover price cannot change the rendered figures between runs
        await pinPrice(api, await adminToken(api), 50);
        await seedWalkData(api);
        hold = { armed: false, spec: NEW_PAGE_PROBE, parked: [] };
        await recordLayoutShifts(page);
        await installHold(page, hold);
      });

      test('signing in lands on the admin dashboard without it changing afterwards', async ({
        page
      }, testInfo) => {
        await page.mouse.move(0, 0);
        await page.goto('/admin/login');
        await expect(page.getByLabel('Login name')).toBeVisible();
        await page.getByLabel('Login name').fill(ADMIN.loginName);
        await page.getByLabel('Password').fill(ADMIN.password);
        await page.getByLabel('Authenticator code').fill(TOTP_CODE);

        // the sign-in form is outside both shells, so this is the one transition in the walk that is
        // legitimately allowed to build a new header
        await expectSettled(
          page,
          hold,
          testInfo,
          'admin login -> admin landing',
          () => clickAndPark(page, () => page.getByRole('button', { name: 'Sign in' }).click()),
          NEW_PAGE_PROBE,
          'rebuilt'
        );
      });

      for (const { name, link, heading } of [
        { name: 'users', link: 'Manage users', heading: 'Users' },
        { name: 'activity', link: 'Activity', heading: 'All activity' },
        { name: 'expenses', link: 'Expenses', heading: "This user's purchases" },
        { name: 'kitty', link: 'Kitty', heading: 'Kitty balance' },
        { name: 'price', link: 'Price', heading: 'Current price per cup' },
        { name: 'ratings', link: 'Ratings', heading: null },
        { name: 'security', link: 'Security', heading: 'Two-factor authentication' },
        { name: 'profile', link: 'My profile', heading: 'Your details' }
      ] as const) {
        test(`the admin ${name} page and the way back do not change after they appear`, async ({
          page
        }, testInfo) => {
          await loginAsAdmin(page);
          await page.mouse.move(0, 0);

          await expectSettled(page, hold, testInfo, `admin landing -> ${name}`, () =>
            clickAndPark(page, () => page.getByRole('link', { name: link, exact: true }).click())
          );
          if (heading) {
            await expect(page.getByRole('heading', { name: heading })).toBeVisible();
          }

          await expectSettled(page, hold, testInfo, `${name} -> admin landing`, () =>
            clickAndPark(page, () => backArrow(page).click())
          );
        });
      }

      test('the user landing, ratings, and profile do not change after they appear', async ({
        page
      }, testInfo) => {
        await page.goto(`/login/${USER_TOKENS.maxmustermann}`);
        await expect(page.getByRole('heading', { name: 'Recent activity' })).toBeVisible();
        await page.mouse.move(0, 0);

        await expectSettled(page, hold, testInfo, 'user landing -> ratings', () =>
          clickAndPark(page, () => page.getByRole('link', { name: 'Ratings' }).click())
        );
        await expectSettled(page, hold, testInfo, 'ratings -> user landing', () =>
          clickAndPark(page, () => backArrow(page).click())
        );
        await expectSettled(page, hold, testInfo, 'user landing -> profile', () =>
          clickAndPark(page, () => page.getByRole('link', { name: 'Profile' }).click())
        );
        await expectSettled(page, hold, testInfo, 'profile -> user landing', () =>
          clickAndPark(page, () => backArrow(page).click())
        );
      });

      test('switching the viewed user does not change the landing after it appears', async ({
        page
      }, testInfo) => {
        const token = await adminToken(api);
        const users = await api
          .get('/api/users', { headers: { Authorization: `Bearer ${token}` } })
          .then((response) => response.json() as Promise<{ id: string; loginName: string }[]>);
        const other = users.find((user) => user.loginName === 'maxmustermann')!;

        await loginAsAdmin(page);
        await page.mouse.move(0, 0);
        await page.getByRole('combobox', { name: 'User' }).click();
        // the same route is re-resolved, so the `.page` element is reused and cannot mark the new page;
        // the URL is what changes, so the gate parks on the destination URL instead
        const target = new URL(`/admin?user=${other.id}`, page.url()).href;
        await expectSettled(
          page,
          hold,
          testInfo,
          'admin landing -> another user',
          () =>
            clickAndPark(page, () => page.getByRole('option', { name: new RegExp(other.loginName) }).click()),
          { kind: 'url', href: target }
        );
      });
    });
  }
});

/**
 * The behaviours the settle diff cannot see: how many requests a page issues, where a navigation leaves the
 * scroll, what a cold visit shows before any route exists, and the two states an earlier round of fixes
 * kept getting wrong.
 *
 * Known and out of scope: on a cold first visit of a session the Material icon ligatures render as their
 * text names for a frame while the hashed icon font downloads. No static preload can name a hashed file, the
 * persistent shell makes it happen once rather than once per page, and it moves no layout, because a
 * `.mat-icon` is a fixed 24px box.
 */
test.describe('layout stability, beyond the settle diff', () => {
  let api: APIRequestContext;

  test.beforeAll(async () => {
    api = await apiContext();
  });

  test.afterAll(async () => {
    await api.dispose();
  });

  test.beforeEach(async () => {
    await resetFixtures(api);
    await pinPrice(api, await adminToken(api), 50);
    await seedWalkData(api);
  });

  test('opening the admin profile fetches the user and their code exactly once each', async ({ page }) => {
    const requests: string[] = [];
    page.on('request', (request) => requests.push(`${request.method()} ${new URL(request.url()).pathname}`));

    await loginAsAdmin(page);
    requests.length = 0;
    await page.getByRole('link', { name: 'My profile', exact: true }).click();
    await expect(page.getByRole('heading', { name: 'Your details' })).toBeVisible();
    await expect(page.locator('.cc-qr-img')).toBeVisible();

    // `/api/users/me` is the shared directory revalidating behind the page, not this page's own fetch
    const user = requests.filter(
      (request) => /^GET \/api\/users\/[^/]+$/.test(request) && request !== 'GET /api/users/me'
    );
    const qr = requests.filter((request) => /^GET \/api\/users\/[^/]+\/qr\.png$/.test(request));
    expect(user, `expected one user fetch, got ${JSON.stringify(user)}`).toHaveLength(1);
    expect(qr, `expected one QR fetch, got ${JSON.stringify(qr)}`).toHaveLength(1);
  });

  test('switching user keeps the picker in view, and opening a subpage returns to the top', async ({
    page
  }) => {
    const token = await adminToken(api);
    const users = (await (
      await api.get('/api/users', { headers: { Authorization: `Bearer ${token}` } })
    ).json()) as { id: string; loginName: string }[];
    const other = users.find((user) => user.loginName === 'maxmustermann')!;

    // A short viewport, so both the page being left and the page being opened are genuinely scrollable.
    // At the default height they are not, and "the destination is at offset 0" would then hold because the
    // destination cannot hold any other offset, whatever the router did.
    await page.setViewportSize({ width: 800, height: 400 });
    await loginAsAdmin(page);
    await page.mouse.move(0, 0);
    await page.evaluate(() => window.scrollTo(0, 200));
    expect(
      await page.evaluate(() => window.scrollY),
      'the landing must be tall enough to scroll for this test to mean anything'
    ).toBeGreaterThan(0);

    // The same page with a new subject: the figures swap in place and the control the reader just used is
    // still in front of them.
    //
    // This is a smoke assertion, not a gate, and deliberately so: measured on the running app, the switch
    // lands at scroll offset 0 whether or not `onUserChange` passes `scroll: 'manual'`. The picker is the
    // first card in the page body, so Material's own focus restore brings it back to the top of the
    // viewport, and the router's scroll restoration would land in the same place. The property that IS
    // gated is the one below, where the destination is a different page.
    const picker = page.getByRole('combobox', { name: 'User' });
    await picker.click();
    await page.getByRole('option', { name: new RegExp(other.loginName) }).click();
    await expect(page).toHaveURL(new RegExp(`user=${other.id}`));
    await expect(page.locator('.cc-count-card .display')).toHaveText('1');
    await expect(picker).toBeInViewport();

    // A different page: the reader starts at its top rather than part-way down it. The click is dispatched
    // from inside the page rather than through Playwright, which scrolls a target into view before clicking
    // it: with the link in the header, that would put the page back at offset 0 before the navigation even
    // started, and the assertion below would hold no matter what the router did.
    await page.evaluate(() => window.scrollTo(0, 200));
    expect(await page.evaluate(() => window.scrollY)).toBeGreaterThan(0);
    await page.evaluate(() =>
      document.querySelector<HTMLElement>('cc-app-header a[aria-label="Price"]')?.click()
    );
    await expect(page.getByRole('heading', { name: 'Current price per cup' })).toBeVisible();
    expect(
      await page.evaluate(() => document.documentElement.scrollHeight - window.innerHeight),
      'the destination must be scrollable, or it could only ever be at offset 0'
    ).toBeGreaterThan(0);
    await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(0);
  });

  for (const { audience, path } of [
    { audience: 'user', path: `/login/${USER_TOKENS.maxmustermann}` },
    { audience: 'admin', path: '/admin' }
  ] as const) {
    test(`a cold ${audience} visit shows the header bar from the first frame and never moves it`, async ({
      page
    }) => {
      if (audience === 'admin') {
        await loginAsAdmin(page);
      }

      // Sample every animation frame, so "the bar was there the whole time" is measured rather than assumed.
      // Frames before the first bar are counted too (`before`), which is what makes the boot placeholder in
      // index.html load-bearing: without it, every frame of the bundle-parse window would land there.
      await page.addInitScript(() => {
        const seen = { ever: false, gaps: 0, before: 0 };
        (window as unknown as { __ccHeaderFrames: typeof seen }).__ccHeaderFrames = seen;
        const sample = (): void => {
          if (document.body) {
            if (document.querySelector('.cc-header-bar')) {
              seen.ever = true;
            } else if (seen.ever) {
              seen.gaps++;
            } else {
              seen.before++;
            }
          }
          requestAnimationFrame(sample);
        };
        requestAnimationFrame(sample);
      });

      // hold every API response back, so the window between the bundle running and the first route
      // activating is long enough to observe at all
      await page.route('**/api/**', async (route) => {
        await new Promise((resolve) => setTimeout(resolve, 700));
        await route.continue();
      });

      await page.goto(path, { waitUntil: 'commit' });

      const skeletonBar = page.locator('cc-page-skeleton .cc-header-bar');
      await expect(skeletonBar).toBeVisible();
      const skeletonRect = await skeletonBar.boundingBox();

      const headerBar = page.locator('cc-app-header .cc-header-bar');
      await expect(headerBar).toBeVisible({ timeout: 15_000 });
      expect(
        await headerBar.boundingBox(),
        'the real header must land exactly where the skeleton drew it'
      ).toEqual(skeletonRect);

      const frames = await page.evaluate(
        () =>
          (window as unknown as { __ccHeaderFrames: { ever: boolean; gaps: number; before: number } })
            .__ccHeaderFrames
      );
      expect(frames.ever, 'a header bar should have been on screen').toBe(true);
      expect(frames.gaps, 'no frame should have been without a header bar once one appeared').toBe(0);
      // The document's own markup carries the bar, so it is there as soon as the body is. One frame of
      // slack covers the frame in which the body element exists but its children have not been parsed.
      expect(
        frames.before,
        'the bar should be there from the frame the document body is'
      ).toBeLessThanOrEqual(1);
    });
  }

  test('a landing loaded inside the grace window offers its suggested bean, ready to rate', async ({
    page
  }) => {
    // the seed adds a coffee for this user and rates it, so the page loads with a live rating prompt
    await page.goto(`/login/${USER_TOKENS.maxmustermann}`);

    const ratingCard = page.locator('.cc-rating-card');
    await expect(ratingCard.locator('mat-select')).toContainText('Layout Roast');
    // the score buttons are disabled while no bean is chosen, so this is what proves the preselection took
    await expect(ratingCard.getByRole('button', { name: '5 out of 5' })).toBeEnabled();
  });

  test('switching user closes the correction form and shows the new user data', async ({ page }) => {
    const token = await adminToken(api);
    const users = (await (
      await api.get('/api/users', { headers: { Authorization: `Bearer ${token}` } })
    ).json()) as { id: string; loginName: string }[];
    const other = users.find((user) => user.loginName === 'maxmustermann')!;

    await loginAsAdmin(page);
    await page.getByRole('button', { name: 'Edit total' }).click();
    await expect(page.getByLabel('New total')).toBeVisible();

    await page.getByRole('combobox', { name: 'User' }).click();
    await page.getByRole('option', { name: new RegExp(other.loginName) }).click();

    await expect(page.getByLabel('New total')).toBeHidden();
    // the admin was seeded with two cups and this user with one, so the count proves whose page this is
    await expect(page.locator('.cc-count-card .display')).toHaveText('1');
  });

  test('switching user closes the profile edit form and shows the new user details', async ({ page }) => {
    const token = await adminToken(api);
    const users = (await (
      await api.get('/api/users', { headers: { Authorization: `Bearer ${token}` } })
    ).json()) as { id: string; loginName: string }[];
    const other = users.find((user) => user.loginName === 'maxmustermann')!;

    await loginAsAdmin(page);
    await page.getByRole('link', { name: 'My profile', exact: true }).click();
    await page.getByRole('button', { name: 'Edit your details' }).click();
    await expect(page.getByLabel('First name')).toBeVisible();

    await page.getByRole('combobox', { name: 'User' }).click();
    await page.getByRole('option', { name: new RegExp(other.loginName) }).click();

    await expect(page.getByLabel('First name')).toBeHidden();
    // the heading names whose account this is, so it proves the switch landed rather than only closing
    await expect(page.getByRole('heading', { name: 'User details' })).toBeVisible();
  });

  test('switching user abandons a purchase correction and shows the new user purchases', async ({ page }) => {
    const token = await adminToken(api);
    const users = (await (
      await api.get('/api/users', { headers: { Authorization: `Bearer ${token}` } })
    ).json()) as { id: string; loginName: string }[];
    const other = users.find((user) => user.loginName === 'maxmustermann')!;

    await loginAsAdmin(page);
    await page.getByRole('link', { name: 'Expenses', exact: true }).click();
    // the seed records one purchase on the admin's own account, which is the default subject here
    await page.getByRole('button', { name: 'Correct purchase' }).first().click();
    await expect(page.getByRole('heading', { name: 'Correct a purchase' })).toBeVisible();

    await page.getByRole('combobox', { name: 'User' }).click();
    await page.getByRole('option', { name: new RegExp(other.loginName) }).click();

    await expect(page.getByRole('heading', { name: 'Record a purchase' })).toBeVisible();
    await expect(page.getByText('No purchases recorded for this user yet.')).toBeVisible();
  });
});
