'use strict';

const { runReadOnlyPageScenario } = require('./_shared');

function assessCapability(kind, payload, ui) {
  const data = payload?.success === true ? payload.data : null;
  const capability = data?.[kind];
  const contractMatches = data?.contractVersion === 'scheduling-capabilities/v1'
    && capability?.status === 'UNAVAILABLE' && capability?.mode === 'UNAVAILABLE'
    && typeof capability.reason === 'string' && capability.reason.trim().length > 0;
  const consistent = contractMatches && ui.automationDisabled === true
    && ui.reasonVisible === true && ui.manualExplanationVisible === true;
  return { result: consistent ? 'PASS' : 'PARTIAL_DEFECT', rootCauseClass: consistent ? 'none' : 'frontend' };
}

function scenario(kind, id, route, landmark, buttonName, explanation) {
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
              manualExplanationVisible: await page.getByText(explanation, { exact: false }).first().isVisible().catch(() => false),
            };
            return { capabilityResponseObserved: payload !== null, contractVersion: payload?.data?.contractVersion,
              capability, ui, assessment: assessCapability(kind, payload, ui) };
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
    '人工确认的排程草稿', 'AI 智能生成（暂不可用）', '系统不会自动分配工人'),
  workers: scenario('workerOptimization', 'scheduling-worker-capabilities-readonly', '/scheduling/workers',
    '人员分配', 'AI 优化分配（暂不可用）', '添加工人'),
  assessCapability,
};
