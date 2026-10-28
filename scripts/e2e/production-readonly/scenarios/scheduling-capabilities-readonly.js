'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

// expected: { status, mode } the server must report. UNAVAILABLE ⇒ the automation button must be
// disabled; AVAILABLE ⇒ it must be enabled. APS v2 (Steve 2026-10-28): aiGeneration = DAILY_CAPACITY
// (按产品日产能按天排), 取代 v1 的 DAG_DURATION —— 线上还报 DAG_DURATION 就是旧后端, 判后端缺陷。
const UNAVAILABLE = { status: 'UNAVAILABLE', mode: 'UNAVAILABLE' };
const CREATE_EXPECTED = Object.freeze({ status: 'AVAILABLE', mode: 'DAILY_CAPACITY' });
const CREATE_BUTTON = '按日产能自动排程';

function assessCapability(kind, payload, ui, expected = UNAVAILABLE) {
  if (payload === null) return { result: 'UNVERIFIED', rootCauseClass: 'tool' };
  const data = payload?.success === true ? payload.data : null;
  const capability = data?.[kind];
  const contractMatches = data?.contractVersion === 'scheduling-capabilities/v1'
    && capability?.status === expected.status && capability?.mode === expected.mode
    && typeof capability.reason === 'string' && capability.reason.trim().length > 0;
  const buttonMatches = expected.status === 'AVAILABLE'
    ? ui.automationDisabled === false : ui.automationDisabled === true;
  const consistent = contractMatches && buttonMatches
    && ui.reasonVisible === true && ui.manualExplanationVisible === true;
  return { result: consistent ? 'PASS' : 'PARTIAL_DEFECT', rootCauseClass: consistent ? 'none' : contractMatches ? 'frontend' : 'backend' };
}

function scenario(kind, id, route, landmark, buttonName, explanation, expected = UNAVAILABLE) {
  return {
    id,
    defaultEnabled: false,
    async run(ctx) {
      let payload = null;
      const reads = [];
      const endpoint = `/api/mobile/${encodeURIComponent(ctx.expectedFactoryId)}/scheduling/capabilities`;
      const listener = response => {
        if (response.request().method() !== 'GET' || new URL(response.url()).pathname !== endpoint) return;
        reads.push(response.json().then(body => { if (response.ok()) payload = body; }).catch(() => {}));
      };
      ctx.page.on('response', listener);
      try {
        return await runReadOnlyPageScenario(ctx, {
          id, path: route, landmarks: [landmark], screenshot: true,
          inspect: async page => {
            await Promise.all(reads);
            const capability = payload?.data?.[kind];
            const button = page.getByRole('button', { name: buttonName });
            const ui = {
              automationDisabled: await button.isDisabled().catch(() => false),
              reasonVisible: typeof capability?.reason === 'string'
                && await page.getByText(capability.reason, { exact: true }).first().isVisible().catch(() => false),
              manualExplanationVisible: await page.getByText(explanation, { exact: true }).first().isVisible().catch(() => false),
            };
            return { capabilityResponseObserved: payload !== null, contractVersion: payload?.data?.contractVersion,
              capability, ui, assessment: assessCapability(kind, payload, ui, expected) };
          },
        });
      } finally {
        ctx.page.off('response', listener);
      }
    },
  };
}

module.exports = {
  create: scenario('aiGeneration', 'scheduling-create-capabilities-readonly', '/scheduling/plans/create',
    '人工确认的排程草稿', CREATE_BUTTON, '请逐项填写产线、基本单位整数数量和起止时间；系统不会自动分配工人，也不会替你确认工艺、物料或换产可行性。',
    CREATE_EXPECTED),
  workers: scenario('workerOptimization', 'scheduling-worker-capabilities-readonly', '/scheduling/workers',
    '人员分配', 'AI 优化分配（暂不可用）', '请使用每个排程卡片上的“添加工人”进行人工指派。'),
  assessCapability,
  CREATE_EXPECTED,
  CREATE_BUTTON,
};
