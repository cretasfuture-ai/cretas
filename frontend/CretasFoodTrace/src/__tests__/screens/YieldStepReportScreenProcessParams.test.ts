/**
 * 产出报工带工艺参数的接线契约 (源码级, 与同目录 MaterialAdvisory 测试同一手法):
 *  - customFields 只出现在 OUTPUT 请求 (doSubmitOutput), INPUT / SEGMENT 构造处不含;
 *  - 超标确认含「工艺参数超出标准」明细, 按钮「返回修改」(cancel, 不碰 state) / 「按实际值报工」;
 *  - 参数 state 只在换道 / 提交成功时清, 取消确认不清。
 */
import fs from 'fs';
import path from 'path';

const src = fs.readFileSync(
  path.resolve(__dirname, '../../screens/processing/YieldStepReportScreen.tsx'),
  'utf8',
).replace(/\r\n/g, '\n');

function between(start: string, end: string): string {
  const a = src.indexOf(start);
  expect(a).toBeGreaterThan(-1);
  const b = src.indexOf(end, a + start.length);
  expect(b).toBeGreaterThan(a);
  return src.slice(a, b);
}

describe('YieldStepReportScreen 工艺参数接线', () => {
  it('customFields 只在 OUTPUT 提交 (doSubmitOutput) 里构造', () => {
    expect(src.match(/customFields:\s*paramBuild\.customFields/g)).toHaveLength(1);
    const outputFn = between('const doSubmitOutput = useCallback', 'const handleSavePartialOutput');
    expect(outputFn).toContain("reportKind: 'OUTPUT'");
    expect(outputFn).toContain('customFields: paramBuild.customFields');
    expect(outputFn).toContain('buildCustomFieldsRequest(paramDefs, paramValues)');
    // 其余 reportKind 的构造处都不带
    const inputAndSegment = between('const handleSubmitSegment', 'const submitOutputWithForce');
    expect(inputAndSegment).not.toContain('customFields');
    expect(between('const handleSubmitInput', 'const handleSubmitSegment')).not.toContain('customFields');
  });

  it('哨兵任务 / 无 workProcessId 不显示也不发参数', () => {
    const defs = between('const paramDefs = useMemo', 'const ccpReadingsForRequest');
    expect(defs).toContain('isSentinelBatch');
    expect(defs).toContain('!currentTask?.workProcessId');
  });

  it('超标确认: 列出明细, 返回修改 = cancel 且不动 state, 按实际值报工才提交', () => {
    const confirm = between('const handleSubmitOutput = useCallback', '// ========================= 完成阶段');
    expect(confirm).toContain('collectParamDeviations(paramDefs, paramValues)');
    expect(confirm).toContain('工艺参数超出标准：');
    expect(confirm).toContain("paramDeviations.map((d) => d.text).join('\\n')");
    expect(confirm).toContain("{ text: '返回修改', style: 'cancel' }");
    expect(confirm).toContain("{ text: '按实际值报工', style: 'default', onPress: () => doSubmitOutput(true) }");
    // 取消按钮没有 onPress, 也不在确认流程里清参数
    expect(confirm).not.toContain('setParamValues');
  });

  it('参数 state 只在换道 / 产出成功后清', () => {
    const clears = src.match(/setParamValues\(\{\}\)/g) ?? [];
    expect(clears).toHaveLength(2);   // 换道 effect + afterCcpReport (仅 OUTPUT 成功路径调用)
    const after = between('const afterCcpReport = useCallback', 'setCcpValues({})');
    expect(after).toContain('setParamValues({})');
    // afterCcpReport 只在 OUTPUT 的两条提交路径里被调用
    expect(src.match(/afterCcpReport\(res\.data\.ccp\)/g)).toHaveLength(2);
  });

  it('留单续报带超标参数: 同样二次确认, 返回修改不提交', () => {
    const partial = between('const handleSavePartialOutput = useCallback', '// 完工二次确认');
    expect(partial).toContain('collectParamDeviations(paramDefs, paramValues)');
    expect(partial).toContain("{ text: '返回修改', style: 'cancel' }");
    expect(partial).toContain("{ text: '按实际值保存', style: 'default', onPress: () => doSubmitOutput(false) }");
    expect(partial).not.toContain('setParamValues');
  });

  it('数字字段填了非数字: 提交与确认前都挡下', () => {
    expect(src.match(/工艺参数请填数字/g)).toHaveLength(2);
  });
});
