import fs from 'fs';
import path from 'path';

const screenSource = fs.readFileSync(
  path.resolve(__dirname, '../../../screens/processing/ProductionPlanManagementScreen.tsx'),
  'utf8',
);

const srcRoot = path.resolve(__dirname, '../../../');
const dispatcherDetailSource = fs.readFileSync(path.join(srcRoot, 'screens/dispatcher/plan/PlanDetailScreen.tsx'), 'utf8');
const planApiSource = fs.readFileSync(path.join(srcRoot, 'services/api/productionPlanApiClient.ts'), 'utf8');

/** 非测试的 ts/tsx 源文件。 */
function listSources(dir: string): string[] {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) return entry.name === '__tests__' || entry.name === 'node_modules' ? [] : listSources(full);
    return /\.(ts|tsx)$/.test(entry.name) && !/\.test\.tsx?$/.test(entry.name) ? [full] : [];
  });
}

describe('production plan mobile boundary', () => {
  it('does not model a separate start-production action', () => {
    expect(screenSource).not.toContain('handleStartProduction');
    expect(screenSource).not.toContain('确认开始生产');
  });

  it('explains the PC and operator handoff', () => {
    expect(screenSource).toContain('创建或调整生产计划、结单请使用 PC');
    expect(screenSource).toContain('现场操作员在各自任务中录入工序报工');
  });

  it('手机上没有任何完工入口 (结单 / 完工只在 PC; 2026-11-12 删掉两处没接线的完工弹窗)', () => {
    for (const forbidden of ['canCompletePlan', 'openCompleteDialog', 'handleCompleteProduction', 'showCompleteDialog', '确认完成']) {
      expect(screenSource).not.toContain(forbidden);
    }
    for (const forbidden of ['handleCompletePlan', 'submitCompletePlan', 'complete-plan-modal', '确定完成']) {
      expect(dispatcherDetailSource).not.toContain(forbidden);
    }
  });

  it('App 里没有任何代码调计划完工接口 (POST /production-plans/{id}/complete)', () => {
    expect(planApiSource).not.toMatch(/async\s+completeProduction\s*\(/);
    expect(planApiSource).not.toMatch(/\/complete[`'"]/);
    const offenders = listSources(srcRoot).filter((file) => /productionPlanApiClient\.completeProduction\s*\(/.test(fs.readFileSync(file, 'utf8')));
    expect(offenders).toEqual([]);
  });

  it('阳性对照: 扫描确实读到了源码 (否则上面的「没有」没有意义)', () => {
    const files = listSources(srcRoot);
    expect(files.length).toBeGreaterThan(200);
    expect(files.some((f) => f.endsWith(path.join('dispatcher', 'plan', 'PlanDetailScreen.tsx')))).toBe(true);
    expect(planApiSource).toContain('async startProduction(');
  });
});
