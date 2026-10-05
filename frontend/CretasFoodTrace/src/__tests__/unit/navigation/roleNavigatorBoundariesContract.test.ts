import fs from 'fs';
import path from 'path';

const read = (relativePath: string) => fs.readFileSync(
  path.resolve(__dirname, `../../../${relativePath}`),
  'utf8',
);

/**
 * 剥掉注释再断言。
 *
 * ⚠️ 2026-08-15 实测的坑：给 BossNavigator 移除「审批」tab 时，我在原地留了一段
 * 说明为什么移除的注释，那段注释里**提到了 `OATodoStackNavigator` 这个名字**。
 * 如果闸直接扫原文，`toContain('OATodoStackNavigator')` 会**继续通过** ——
 * 而组件其实已经不挂了。这就是「闸把自己的文档也数了进去」。
 */
const readCode = (relativePath: string) => read(relativePath)
  .replace(/\/\*[\s\S]*?\*\//g, ' ')
  .replace(/\/\/.*/g, ' ');

describe('role-specific mobile navigator boundaries', () => {
  it('keeps the factory boss on overview, analysis and account only (no empty approvals tab)', () => {
    const source = readCode('navigation/BossNavigator.tsx');
    expect(source).toContain('BossOverviewStack');
    // 2026-08-15 (Steve 拍板): 移除「审批」tab —— 它对工厂超管永远是空的。
    // 后端 ROLE_TYPES 只有 finance_manager / cashier; 且 prod 实测
    // approval_workflow_instances 0 行, 配了链的两家从来没有过采购单。
    // 留一个永远空的入口比没有入口更糟。OA 待办中心 = 财务/出纳专用。
    expect(source).not.toContain('OATodoStackNavigator');
    expect(source).toContain('SmartBIStackNavigator');
    expect(source).toContain('MobileAccountScreen');
    expect(source).not.toContain('FAManagementStackNavigator');
    expect(source).not.toContain('SalesOrderCreateScreen');
    expect(source).not.toContain('PurchaseOrderCreateScreen');
  });

  it('keeps warehouse workers on task stacks without manager home or profile management', () => {
    const source = read('navigation/WarehouseWorkerNavigator.tsx');
    expect(source).toContain('WHInboundStackNavigator');
    expect(source).toContain('WHOutboundStackNavigator');
    expect(source).toContain('WHInventoryStackNavigator');
    expect(source).toContain('MobileAccountScreen');
    expect(source).not.toContain('WHHomeStackNavigator');
    expect(source).not.toContain('WHProfileStackNavigator');
  });

  /**
   * 2026-10-05 owner 裁定（Line B）：一线角色**默认就有** AI 助手，开关是熔断开关。
   * 这条原来写的是「仓库员导航器里不许出现 AIChatScreen」—— 守的是旧需求（形态 C‴），
   * 现在抬到性质：AI 对话**只能**经由熔断开关注册，且只注册一次（不许出现一条不受开关约束的旁路）。
   * 行为层（开关关 ⇒ 入口和路由都没有）在 integration/navigation/FrontlineAIEntryNavigation.test.tsx。
   */
  it.each([
    'navigation/WarehouseWorkerNavigator.tsx',
    'navigation/OperatorNavigator.tsx',
    'navigation/QualityInspectorNavigator.tsx',
  ])('%s registers AIChat only behind the frontline AI kill switch', (file) => {
    const code = readCode(file);
    expect(code).toContain('useFrontlineAIEntryEnabled()');
    expect(code.match(/name="AIChat"/g) ?? []).toHaveLength(1);
    expect(code).toMatch(/\{aiEntryEnabled && \(\s*<RootStack\.Screen\s+name="AIChat"/);
  });

  it('阳性对照: 上面那条正则对一条不受开关约束的注册确实不匹配', () => {
    const ungated = '<RootStack.Screen name="AIChat" component={AIChatScreen} />';
    expect(ungated).not.toMatch(/\{aiEntryEnabled && \(\s*<RootStack\.Screen\s+name="AIChat"/);
    const gated = '{aiEntryEnabled && (\n        <RootStack.Screen name="AIChat" component={AIChatScreen} />\n      )}';
    expect(gated).toMatch(/\{aiEntryEnabled && \(\s*<RootStack\.Screen\s+name="AIChat"/);
  });

  it('locks unknown roles instead of granting a generic business workspace', () => {
    const source = read('navigation/RestrictedRoleNavigator.tsx');
    expect(source).toContain('不会自动开放通用业务入口');
    expect(source).toContain('退出登录');
  });
});
