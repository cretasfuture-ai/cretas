'use strict';

module.exports = {
  testDir: __dirname,
  testMatch: 'parallel-harness.fixture.spec.js',
  timeout: 90_000,
  fullyParallel: false,
  workers: 1,
  use: { headless: true },
  reporter: [['line']],
};
