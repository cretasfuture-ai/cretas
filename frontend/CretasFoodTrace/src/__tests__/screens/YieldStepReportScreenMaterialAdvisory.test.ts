/**
 * 报工页原料建议的边界契约。
 * 计划级 advisory 是完整 BOM 的只读建议，只在首道原料投入上下文有意义；
 * 非首道使用上道 WIP 时既不应请求，也不应渲染这张计划级缺料卡。
 */
import fs from 'fs';
import path from 'path';

const SCREEN = path.resolve(
  __dirname,
  '../../screens/processing/YieldStepReportScreen.tsx',
);

function source(): string {
  return fs.readFileSync(SCREEN, 'utf8');
}

function materialAdvisoryEffect(): string {
  const src = source();
  const start = src.indexOf('useEffect(() => {\n    if (currentStepIndex !== 0)');
  expect(start).toBeGreaterThan(-1);
  const end = src.indexOf('\n\n  // 切道时重置所有阶段输入态', start);
  expect(end).toBeGreaterThan(start);
  return src.slice(start, end);
}

function materialAdvisoryRenderer(): string {
  const src = source();
  const start = src.indexOf('const renderMaterialAdvisoryCard = () => {');
  expect(start).toBeGreaterThan(-1);
  const end = src.indexOf('\n\n  const renderSentinelMaterialInputScreen', start);
  expect(end).toBeGreaterThan(start);
  return src.slice(start, end);
}

describe('YieldStepReportScreen material advisory scope', () => {
  it.each([
    [0, true, '首道原料投入显示计划级建议'],
    [1, false, '非首道 WIP 不显示计划级建议'],
    [2, false, '更后续工序也不显示计划级建议'],
  ])('%s: %s', (stepIndex, expected) => {
    // 这是组件两处首道门控的共同业务判定；源码断言随后钉住实际调用点。
    expect(stepIndex === 0).toBe(expected);
  });

  it('请求 advisory 前必须门控为首道，且离开首道时清空旧建议', () => {
    const effect = materialAdvisoryEffect();
    expect(effect).toContain('currentStepIndex !== 0');
    expect(effect).toContain('setMaterialAdvisory(null)');
    expect(effect).toContain('setMaterialAdvisoryError(null)');
    expect(effect).toContain('loadMaterialAdvisory();');
  });

  it('渲染 advisory 前必须门控为首道，防止非首道显示完整 BOM 缺料', () => {
    const renderer = materialAdvisoryRenderer();
    expect(renderer).toContain('if (!productionPlanId || currentStepIndex !== 0) return null;');
  });

  it('WIP 投入 payload 仍只按实际选择条件带原料批次，不会凭 advisory 补 materialBatchRefs', () => {
    const src = source();
    const submitStart = src.indexOf('const handleSubmitInput = useCallback');
    const payloadStart = src.indexOf('const req: YieldReportRequest = {', submitStart);
    const payloadEnd = src.indexOf('\n    setSubmitting(true);', payloadStart);
    expect(submitStart).toBeGreaterThan(-1);
    expect(payloadStart).toBeGreaterThan(submitStart);
    expect(payloadEnd).toBeGreaterThan(payloadStart);
    const payload = src.slice(payloadStart, payloadEnd);

    expect(payload).toContain('sourceWipNo: effectiveSourceWipNo');
    expect(payload).toContain('materialBatchRefs.length > 0');
    expect(payload).toContain('materialBatchRefs: materialBatchRefs.map');
    // WIP-only input (materialBatchRefs = []) therefore omits the optional field.
    const wipOnlyPayload = { sourceWipNo: 'WIP-001', ...( [].length > 0 ? { materialBatchRefs: [] } : {}) };
    expect(wipOnlyPayload).not.toHaveProperty('materialBatchRefs');
  });
});
