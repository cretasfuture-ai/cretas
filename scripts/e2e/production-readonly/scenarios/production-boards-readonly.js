'use strict';

const followup = require('./visual-unverified-followup-readonly');
const id = 'production-boards-readonly';
const targets = Object.freeze(followup.FOLLOWUP_PAGES.filter(page =>
  ['pending-queue', 'restock-board', 'bom-achievement'].includes(page.id)));

// Independently bounded acceptance. The full follow-up retains label-QC and
// still fails on its blocked telemetry; this scenario never visits that page.
module.exports = { id, defaultEnabled: false, targets,
  run: ctx => followup.run(ctx, targets, id) };
