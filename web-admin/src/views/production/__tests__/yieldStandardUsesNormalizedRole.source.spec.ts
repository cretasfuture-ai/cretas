import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';

/**
 * 角色判断必须走 `authStore.currentRole`，⛔ 不许从 user 对象里自己挖 `role`。
 *
 * <h2>为什么有这道闸（2026-09-05 部署后实测）</h2>
 *
 * <p>「标准出成率维护」上线后用 `liushanmen_admin`（{@code factory_super_admin}）打开，
 * 页面顶上挂着「你的账号只能查看」，三个「填写」按钮 {@code disabled=true} ——
 * <b>超管点不了自己该能点的东西</b>。</p>
 *
 * <p>成因：写的是 {@code authStore.user?.role}，而 {@code cretas_user} 对象
 * <b>没有 role 这个键</b>。实测它的键是：</p>
 * <pre>
 *   id / username / fullName / email / isActive / createdAt / updatedAt / userType / factoryUser
 * </pre>
 * <p>角色在 {@code user.factoryUser.role}（工厂账号）或 {@code user.platformUser.role}（平台账号）。
 * store 里 {@code currentRole} 就是为这件事存在的归一化出口，还额外把
 * 「{@code super_admin} 且无 factoryId」归一成 {@code platform_admin}。</p>
 *
 * <h2>这类 bug 为什么活得久</h2>
 *
 * <p>▎<b>它的症状是「某个人看不到他该看到的东西」，而看不到的人不会来报 bug。</b></p>
 *
 * <p>恒 false 的权限判断在测试里也不显眼：mock 一个带 {@code role} 的假 user，
 * 断言就绿了 —— 假 user 有那个键，真 user 没有。⇒ 判据只能落在<b>源码怎么取角色</b>上。</p>
 *
 * <h2>🔴 全仓同病登记（硬约束 8：故意不改的要显式登记并写理由）</h2>
 *
 * <p>2026-09-05 扫全仓 {@code .vue}，读不存在字段的共 <b>4 处</b>：</p>
 * <ol>
 *   <li>{@code views/production/YieldStandardView.vue} —— 本次修好</li>
 *   <li>{@code views/calibration/CalibrationListView.vue:44}
 *       {@code user?.role === 'platform_admin'} ⇒ 恒 false</li>
 *   <li>{@code views/smart-bi/calibration/CalibrationDashboard.vue:38} 同上 ⇒ 恒 false</li>
 *   <li>{@code views/hr/employees/list.vue:26} {@code user?.roleCode} ⇒ 恒 undefined</li>
 * </ol>
 *
 * <p><b>后三处本次不改</b>，理由：修好它们会让平台管理员<b>突然看到</b>今天看不到的入口 ——
 * 那是正确的行为，但它是<b>别人功能的访问面变化</b>，该由那几个功能的负责人决定何时放开、
 * 并各自验一遍。⛔ 顺手改三个功能的权限面，比留着这个登记更危险。</p>
 *
 * <p>⚠️ 本闸只钉本页。要扩成全仓闸，得先确认那三处的负责人愿意一起改 ——
 * 一道让人不得不去改别人功能的闸，最终会被加 ignore 关掉（形态 E）。</p>
 */

const SCREEN = resolve(__dirname, '../YieldStandardView.vue');

describe('标准出成率维护: 角色取法', () => {
  it('阳性对照: 读到的是这一页, 且它确实做了角色判断', () => {
    const src = readFileSync(SCREEN, 'utf8');
    expect(src.length, '源码读成空 ⇒ 下面全是恒真式').toBeGreaterThan(2000);
    expect(src).toContain('WRITE_ROLES');
    expect(src).toContain('canEdit');
  });

  it('用 authStore.currentRole, 不从 user 对象里挖 role', () => {
    const src = readFileSync(SCREEN, 'utf8');
    // 判据落在**会执行的代码**上: 注释里提到 user?.role 是在解释这个 bug, 不算违例
    const code = src
      .replace(/<!--[\s\S]*?-->/g, ' ')
      .replace(/\/\*[\s\S]*?\*\//g, ' ')
      .replace(/^\s*\/\/.*$/gm, ' ');

    expect(code, '剥注释剥过头 ⇒ 下面的断言无意义').toContain('canEdit');
    expect(code, '没用归一化出口 authStore.currentRole').toContain('authStore.currentRole');
    expect(code, '🔴 从 user 对象里挖 role —— 那个键不存在, 判断恒 false')
      .not.toMatch(/authStore\.user\s*\??\.\s*(role|roleCode)/);
  });

  it('白名单里是超管两种角色 —— 与后端 YIELD_STANDARD_WRITE_ROLES 同一口径', () => {
    const src = readFileSync(SCREEN, 'utf8');
    expect(src).toContain('platform_admin');
    expect(src).toContain('factory_super_admin');
    // 阴性对照: ⛔ 不许把 production:read_write 那批角色放进来 ——
    // 后端实测那 9 个角色含 operator / yield_operator, 前端放宽就与后端不同口径了
    for (const role of ['operator', 'yield_operator', 'team_leader', 'production_clerk']) {
      expect(src, `白名单里出现了 ${role} —— 与后端收窄后的口径不一致`).not.toContain(`'${role}'`);
    }
  });
});
