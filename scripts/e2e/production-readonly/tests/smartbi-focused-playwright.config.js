'use strict';

module.exports = {
  testDir: __dirname,
  testMatch: 'smartbi-focused-acceptance.fixture.spec.js',
  timeout: 30_000,
  fullyParallel: false,
  workers: 1,
  use: { headless: true, screenshot: 'only-on-failure' },
  reporter: [['line']],
};
