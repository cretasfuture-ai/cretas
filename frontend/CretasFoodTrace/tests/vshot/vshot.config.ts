import { defineConfig, devices } from '@playwright/test';

/**
 * Standalone config for the RN visual walkthrough.
 *
 * ⚠️ 跑之前必读：改了 App 源码之后**必须重启 metro**（`expo start --web --clear`）。
 * 这套走查用 `CI=1` 起 metro，CI 模式关掉了文件监听 —— 改完源码直接重跑，
 * 拿到的还是**改之前的 bundle**，而屏幕上看起来就是「修了但没生效」。
 * 2026-08-28 实测踩过一次：仓储主管的重复路由已经删掉了，重跑仍然白屏，
 * 重启 metro 之后才正常。**「合并了」和「屏上看到了」之间还隔着一个 bundle。**
 *
 * Kept separate from anything the app ships so a walkthrough run can never
 * change how the product is built or tested.
 *
 * Viewport is phone-shaped on purpose: this app is used on a handset in a
 * workshop. Screenshotting it at 1920x1080 would hide exactly the truncation
 * and overlap defects the walkthrough is looking for.
 */
export default defineConfig({
  testDir: '.',
  timeout: 15 * 60 * 1000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: process.env.VSHOT_APP ?? 'http://localhost:3021',
    headless: false, // project hard rule: playwright runs headed
    viewport: { width: 412, height: 915 }, // Pixel-class handset
    deviceScaleFactor: 2,
    locale: 'zh-CN',
    ignoreHTTPSErrors: true,
    actionTimeout: 20_000,
    launchOptions: {
      args: [
        '--lang=zh-CN',
        // The app is served from localhost but talks to the prod gateway.
        // Real devices have no same-origin constraint; the browser does.
        '--disable-web-security',
        '--disable-features=IsolateOrigins,site-per-process',
      ],
    },
  },
  projects: [
    {
      name: 'rn-web',
      use: { ...devices['Desktop Chrome'], viewport: { width: 412, height: 915 } },
    },
  ],
});
