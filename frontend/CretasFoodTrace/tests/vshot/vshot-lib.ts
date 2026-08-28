/**
 * Shared helpers for the RN visual walkthrough.
 *
 * Two things in here exist only because the round-1 web-admin walkthrough got
 * burned by their absence:
 *
 *  - login() asserts the auth store really holds a session afterwards. A
 *    screenshot of the login screen looks exactly like a screenshot of a
 *    working app to a file-size check, so "we took 300 pictures" is not
 *    evidence that we were logged in for any of them.
 *
 *  - collectRouteNames() reads the route names out of the *running* navigator
 *    (rootState) instead of trusting a static parse of the navigator sources.
 *    The static parse counts commented-out <Stack.Screen> blocks as live routes,
 *    which is exactly the direction that hides a retired screen.
 */
import type { Page } from '@playwright/test';

export type NavPath = string[];

const NAV = '__CRETAS_NAV__';

export async function waitForApp(page: Page, timeout = 180_000) {
  await page.waitForFunction(
    () => document.body && document.body.innerText.trim().length > 0,
    undefined,
    { timeout },
  );
}

export async function navRefReady(page: Page, timeout = 60_000) {
  await page.waitForFunction(
    (nav) => {
      const r = (globalThis as any)[nav];
      return !!r && r.isReady && r.isReady();
    },
    NAV,
    { timeout },
  );
}

/** Log in through the real form. Throws if the session did not actually take. */
export async function login(page: Page, user: string, pass: string) {
  // The landing screen shows a 登录 button before the form appears.
  const landing = page.getByTestId('landing-login-btn');
  if (await landing.count()) {
    await landing.first().click();
  }
  await page.getByTestId('login-username-input').fill(user);
  await page.getByTestId('login-password-input').fill(pass);
  await page.getByTestId('login-submit-btn').click();

  // POSITIVE CONTROL. Not "the login screen went away" -- that also happens on
  // a crash. We require the persisted auth store to name a real user.
  await page.waitForFunction(
    () => {
      const ls = globalThis.localStorage;
      if (!ls) return false;
      for (let i = 0; i < ls.length; i++) {
        const k = ls.key(i);
        if (!k || !k.includes('auth-storage')) continue;
        try {
          const s = JSON.parse(ls.getItem(k) as string);
          if (s?.state?.isAuthenticated && s?.state?.user) return true;
        } catch {
          /* keep looking */
        }
      }
      return false;
    },
    undefined,
    { timeout: 90_000 },
  );
}

export async function authSummary(page: Page) {
  return page.evaluate(() => {
    const ls = globalThis.localStorage;
    if (!ls) return null;
    for (let i = 0; i < ls.length; i++) {
      const k = ls.key(i);
      if (!k || !k.includes('auth-storage')) continue;
      try {
        const s = JSON.parse(ls.getItem(k) as string).state;
        const u = s?.user ?? {};
        return {
          storageKey: k,
          username: u.username,
          userType: u.userType,
          factoryId: u.factoryId ?? u.factoryUser?.factoryId,
          role: u.userType === 'platform' ? u.platformUser?.role : u.factoryUser?.role,
          isAuthenticated: s?.isAuthenticated,
        };
      } catch {
        /* keep looking */
      }
    }
    return null;
  });
}

/** Full navigation state of the running app. */
export async function rootState(page: Page): Promise<any> {
  return page.evaluate((nav) => {
    const r = (globalThis as any)[nav];
    if (!r || !r.isReady || !r.isReady()) return null;
    return JSON.parse(JSON.stringify(r.getRootState()));
  }, NAV);
}

/** Collect every (path -> routeNames) pair visible in a navigation state tree. */
export function collectRouteNames(state: any, prefix: NavPath = [], out: Map<string, string[]> = new Map()) {
  if (!state || !Array.isArray(state.routeNames)) return out;
  out.set(prefix.join('/'), state.routeNames);
  for (const r of state.routes ?? []) {
    if (r.state) collectRouteNames(r.state, [...prefix, r.name], out);
  }
  return out;
}

/** Build the nested params object react-navigation wants for a deep path. */
export function nestedTarget(path: NavPath): [string, any] {
  const [head, ...rest] = path;
  if (rest.length === 0) return [head, undefined];
  let params: any = undefined;
  for (let i = rest.length - 1; i >= 0; i--) {
    params = params === undefined ? { screen: rest[i] } : { screen: rest[i], params };
  }
  return [head, params];
}

export async function navigateTo(page: Page, path: NavPath): Promise<{ ok: boolean; error?: string }> {
  const [head, params] = nestedTarget(path);
  return page.evaluate(
    ({ nav, head, params }) => {
      const r = (globalThis as any)[nav];
      if (!r || !r.isReady || !r.isReady()) return { ok: false, error: 'nav not ready' };
      try {
        r.navigate(head as never, params as never);
        return { ok: true };
      } catch (e: any) {
        return { ok: false, error: String(e?.message ?? e) };
      }
    },
    { nav: NAV, head, params },
  );
}

export async function currentPath(page: Page): Promise<string> {
  const st = await rootState(page);
  const walk = (s: any, acc: string[]): string[] => {
    if (!s || typeof s.index !== 'number') return acc;
    const r = s.routes?.[s.index];
    if (!r) return acc;
    acc.push(r.name);
    return r.state ? walk(r.state, acc) : acc;
  };
  return walk(st, []).join('/');
}

/**
 * Text of the screen that is actually on top.
 *
 * ⛔ `document.body.innerText` is wrong here. react-navigation keeps previously
 * visited screens mounted, and react-native-web hides them with opacity /
 * aria-hidden rather than `display:none` -- which innerText does not filter. The
 * first version of this walkthrough logged the *stack's* text against every
 * route, so screen 13 and screen 16 both came back reading "成品库存" while their
 * screenshots showed two different report pages. The pictures were right and the
 * text was wrong, and the text is what a pattern scan reads.
 *
 * Returns both readings so the difference stays visible instead of being assumed
 * away.
 */
export async function screenText(page: Page): Promise<{ visible: string; raw: string }> {
  return page.evaluate(() => {
    const hidden = (el: Element): boolean => {
      let n: Element | null = el;
      while (n && n !== document.body) {
        if (n.getAttribute('aria-hidden') === 'true') return true;
        const cs = getComputedStyle(n);
        if (cs.display === 'none' || cs.visibility === 'hidden' || Number(cs.opacity) === 0) return true;
        n = n.parentElement;
      }
      return false;
    };

    const parts: string[] = [];
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    let node: Node | null;
    while ((node = walker.nextNode())) {
      const t = (node.textContent ?? '').trim();
      if (!t) continue;
      const el = node.parentElement;
      if (!el) continue;
      if (el.getClientRects().length === 0) continue;
      if (hidden(el)) continue;
      parts.push(t);
    }
    return { visible: parts.join('\n'), raw: document.body.innerText };
  });
}

/**
 * Is the app still standing?
 *
 * An unguarded `route.params` destructure takes the whole tree down to a blank
 * page, and every screenshot after that is the same 3KB white rectangle. Without
 * this check a crash on screen 9 silently turns screens 10..300 into "clean".
 */
export async function navAlive(page: Page): Promise<boolean> {
  try {
    return await page.evaluate((nav) => {
      const r = (globalThis as any)[nav];
      const hasText = !!document.body && document.body.innerText.trim().length > 0;
      return !!(r && r.isReady && r.isReady() && hasText);
    }, NAV);
  } catch {
    return false;
  }
}
