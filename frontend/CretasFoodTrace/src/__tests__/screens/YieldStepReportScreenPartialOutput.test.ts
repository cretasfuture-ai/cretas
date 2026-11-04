/**
 * 留单之后完工的接线契约 (源码级, 与同目录 ProcessParams 测试同一手法):
 *  - 阶段读数经 resolveStepPhase, 任务已结束时不再让人续报;
 *  - 标记完工可不填新增, 留单续报不行 (判定在 canFinishWithoutNewOutput, 只传 markComplete);
 *  - 完工的不平衡提示按整道累计产出算, 不按这一次填的数;
 *  - 已累计产出提示不依赖 yieldLimits 是否取到。
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

describe('YieldStepReportScreen 留单后完工', () => {
  it('阶段读数经 resolveStepPhase, 不直接信报工推导', () => {
    const fn = between('const stepPhaseOf = useCallback', '[yieldData]');
    expect(fn).toContain('resolveStepPhase(task, s.phase)');
  });

  it('提交时按 markComplete 判定能否不填新增, 其余情况仍要求产出量大于 0', () => {
    const fn = between('const doSubmitOutput = useCallback', 'const handleSavePartialOutput');
    expect(fn).toContain('canFinishWithoutNewOutput(outputQty, markComplete, savedOutput)');
    expect(fn).toContain("appAlert('请填写本道产出量', '产出量必须大于 0')");
  });

  it('没有新增就完工时, 成功提示不写「0 盒已记为本道产出」, 有新增时照写', () => {
    const fn = between('const doSubmitOutput = useCallback', 'const handleSavePartialOutput');
    expect(fn).toContain("${finishOnly ? '' : `${output} ${outUnit} 已记为本道产出。`}${cumText}");
  });

  it('完工确认按累计产出算不平衡, 没有新增时写明累计数', () => {
    const fn = between('const handleSubmitOutput = useCallback', '// ========================= 完成阶段');
    expect(fn).toContain('canFinishWithoutNewOutput(outputQty, true, savedOutput)');
    expect(fn).toContain('savedOutput + outVal');
    expect(fn).toContain('这次没有新增产出, 本道累计产出');
  });

  it('已累计产出提示看 savedOutput, 出成上限接口取不到也显示', () => {
    expect(src).toContain('{savedOutput > 0 ? (');
    expect(src).not.toContain('(yieldLimits?.alreadyReported ?? 0) > 0 ?');
  });
});
