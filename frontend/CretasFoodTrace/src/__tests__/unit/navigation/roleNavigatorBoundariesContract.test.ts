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
  it('keeps the factory boss on overview, analysis and account (todo tab only via the backend gate)', () => {
    const source = readCode('navigation/BossNavigator.tsx');
    expect(source).toContain('BossOverviewStack');
    // 2026-08-15 (Steve 拍板) 移除过「审批」tab, 理由是它对工厂超管永远是空的 (当时后端只有财务/出纳有待办)。
    // 2026-11-11 #1611 起待办按权限出数, 厂长拿到全部类型 (含强制插单审批), 「永远空」的前提不在了。
    // 守的性质不变: 不出一个永远空的入口 —— 现在由后端 /my-todos/types 判定, 不再写死「这个岗位没有」(B4)。
    expect(source).not.toContain('OATodoStackNavigator');
    expect(source).toContain("todoTabScreen(Tab, todoEntry, 'boss-tab-oa-todo')");
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

  /**
   * B4 (2026-11-12): 「待办」入口谁看得到只由后端 /my-todos/types 决定 (useMyTodoEntry + todoTabScreen)。
   * 岗位导航器里不许再直接挂 OATodoStackNavigator, 也不许按岗位名开关它 —— 那就是在 App 里抄一份权限映射。
   * 财务导航器 (B4 第二期) 同样只认 /types, 但保留自己的路由名 FinanceTodoTab (首页「待办审批」卡指向它), 见本组最后一条。
   */
  const ROLE_NAVIGATORS_WITH_TODO_GATE = [
    ['navigation/BossNavigator.tsx', 'boss'],
    ['navigation/HRTabNavigator.tsx', 'hr'],
    ['navigation/WorkshopSupervisorTabNavigator.tsx', 'ws'],
    ['navigation/WarehouseManagerTabNavigator.tsx', 'wh'],
    ['navigation/WarehouseWorkerNavigator.tsx', 'warehouse-worker'],
    ['navigation/DispatcherTabNavigator.tsx', 'dispatcher'],
    ['navigation/ProductionManagerNavigator.tsx', 'production-manager'],
    ['navigation/OperationsNavigator.tsx', 'operations'],
    ['navigation/QualityInspectorNavigator.tsx', 'qi'],
    ['navigation/EquipmentAdminNavigator.tsx', 'equipment-admin'],
    ['navigation/QualityManagerNavigator.tsx', 'quality-manager'],
    ['navigation/OperatorNavigator.tsx', 'operator'],
    ['navigation/SalesManagerNavigator.tsx', 'sm'],
    ['navigation/ProcurementManagerNavigator.tsx', 'pm'],
    ['navigation/ViewerNavigator.tsx', 'vw'],
    ['navigation/MainNavigator.tsx', 'main'],
    // B4 第二期 (2026-11-13)
    ['navigation/FactoryAdminTabNavigator.tsx', 'fa'],
    ['navigation/RestrictedRoleNavigator.tsx', 'restricted'],
  ] as const;

  it.each(ROLE_NAVIGATORS_WITH_TODO_GATE)('%s registers the todo tab only through the backend gate', (file, prefix) => {
    const code = readCode(file);
    expect(code).toContain('useMyTodoEntry()');
    expect(code.match(/todoTabScreen\(/g) ?? []).toHaveLength(1);
    expect(code).toContain(`todoTabScreen(Tab, todoEntry, '${prefix}-tab-oa-todo')`);
    expect(code).not.toContain('OATodoStackNavigator');
    expect(code).not.toMatch(/isFinanceOrCashier/);
  });

  it('finance navigator gates its FinanceTodoTab by the same backend entry, not unconditionally', () => {
    const code = readCode('navigation/FinanceNavigator.tsx');
    expect(code).toContain('useMyTodoEntry()');
    expect(code).not.toContain('useMyTodoCount');
    expect(code).toMatch(/\{todoEntry\.visible && \(\s*<Tab\.Screen\s+name="FinanceTodoTab"/);
  });
});
