'use strict';

const { pathnameOf } = require('../core/url-utils');

const FACTORY_ANALYSIS_FACTORY_IDS = Object.freeze(['F006', 'LIUSHANMEN']);
const FACTORY_ANALYSIS_PATH = new RegExp(`^/api/mobile/(${FACTORY_ANALYSIS_FACTORY_IDS.join('|')})/smart-bi/factory-analysis/?$`, 'u');

function assertFactoryAnalysisFactory(factoryId) {
  if (!FACTORY_ANALYSIS_FACTORY_IDS.includes(factoryId)) {
    throw new Error(`Factory analysis tenant is not allowed: ${factoryId || '(missing)'}. Allowed: ${FACTORY_ANALYSIS_FACTORY_IDS.join(', ')}`);
  }
}

function isFactoryAnalysisUrl(url, expectedFactoryId) {
  let pathname;
  try { pathname = pathnameOf(url); } catch { return false; }
  const factoryId = FACTORY_ANALYSIS_PATH.exec(pathname)?.[1];
  return factoryId !== undefined
    && (expectedFactoryId === undefined || factoryId === expectedFactoryId);
}

module.exports = { FACTORY_ANALYSIS_FACTORY_IDS, FACTORY_ANALYSIS_PATH, assertFactoryAnalysisFactory, isFactoryAnalysisUrl };
