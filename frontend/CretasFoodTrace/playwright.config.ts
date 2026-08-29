import { defineConfig, devices } from '@playwright/test';

/**
 * RN(react-native-web) 逐屏走查用的配置。
 *
 * ⚠️ `tests/vshot/` 下的 spec 在仓里已经躺了一阵子，但**没有配套配置** ——
 * 每次跑都靠临时拼一份。那意味着「上次是怎么跑的」不可复现，
 * 也就没法判断两轮读数可不可比。这份就是那一份。
 *
 * 依赖：`@playwright/test` 在**仓库根** package.json 里（不在本目录的 package.json）。
 * 跑法：先起 Metro，再指过去 ——
 *
 *     CI=1 ENVFILE=.env.production EXPO_OFFLINE=1 npx expo start --web --port 3022 --clear
 *     VSHOT_APP=http://localhost:3022 VSHOT_USER=f006_admin VSHOT_OUT=<目录> \
 *       npx playwright test tests/vshot/walkthrough.spec.ts --project=rn-web
 *
 * ⛔ `ENVFILE` 不能漏 —— 漏了 `@env` 是空的，登录直接失败，
 *    而走查会把「一屏没走到」读成「零崩溃」。实测踩过。
 *
 * headed: 仓库规则 `.claude/rules/playwright-headed-mode.md` 要求 E2E 走 headed。
 * 视觉走查更是如此 —— headless 下某些布局/动效不落到最终位置，
 * 而这一轮的产物就是给人眼看的截图。
 *
 * ⛔ 不设 baseURL —— 各 spec 自己用 VSHOT_APP 拼绝对地址, 设了反而多一份会漂的真相。
 *
 * viewport: 412×915 是手机竖屏。RN 屏是按手机排的，
 * 用桌面宽度截出来的图会掩盖真实的换行与截断。
 */
export default defineConfig({
  testDir: './tests',
  timeout: 15 * 60 * 1000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: [['line']],
  use: {
    headless: false,
    viewport: { width: 412, height: 915 },
    deviceScaleFactor: 2,
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    actionTimeout: 20_000,
    navigationTimeout: 60_000,
    screenshot: 'off',
    video: 'off',
    trace: 'off',
  },
  projects: [
    {
      name: 'rn-web',
      use: { ...devices['Desktop Chrome'], viewport: { width: 412, height: 915 }, headless: false },
    },
  ],
});
