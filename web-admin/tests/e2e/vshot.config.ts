import { defineConfig } from '@playwright/test';

/**
 * 逐屏视觉验收专用配置。
 *
 * ⛔ 刻意**不改** `playwright.config.ts` —— 那是多条并行线共用的文件，
 * 而这个走查是一次性工具，没有理由去动共享配置（本仓有并发编辑串台的事故记录）。
 */
export default defineConfig({
  testDir: '.',
  testMatch: /vshot-.*\.spec\.ts/,
  fullyParallel: false,
  workers: 1,
  timeout: 60 * 60 * 1000,
  expect: { timeout: 15_000 },
  use: {
    headless: false,
    viewport: { width: 1920, height: 1080 },
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    launchOptions: {
      args: ['--lang=zh-CN', '--font-render-hinting=none', '--disable-blink-features=AutomationControlled'],
    },
    // ⛔ 关掉 playwright 自己的 screenshot/video：本走查的产出是脚本自己写出来的
    //    那 145 张按屏命名的图，playwright 的 test-results 目录只会制造噪声。
    screenshot: 'off',
    video: 'off',
    trace: 'off',
    navigationTimeout: 30_000,
  },
  reporter: [['list']],
  outputDir: 'test-results-vshot',
});
