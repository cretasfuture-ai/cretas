'use strict';
module.exports = { testDir: __dirname, testMatch: ['fixture.spec.js', 'sidebar-motion.fixture.spec.js'],
  timeout: 30000, fullyParallel: false, workers: 1,
  use: { headless: true, screenshot: 'only-on-failure' }, reporter: [['line']] };
