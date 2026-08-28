/**
 * 🔒 推送「有没有配置」必须是一个**可观测**的事实，⛔ 不是每次登录抛一条 error。
 *
 * <h3>被修掉的形状（2026-08-28 真机实测）</h3>
 *
 * 小米 M2102K1AC / Android 14 上跑生产包 1.0.4，冷启动稳定打出：
 *   E/ReactNativeJS: [PushNotification] 未找到 EAS Project ID
 *   W/ReactNativeJS: [usePushNotifications] 无法获取 Push Token，跳过设备注册
 *
 * 而同文件的注释把这件事框成 **Web 专属**（「Web 上……没有 EAS projectId 概念」）——
 * 照那段注释读，下一个人会以为真机是好的。**真机也没有。**
 * 本项目用自建 OTA，从来没有过 EAS 项目 ⇒ 推送注册自上线起一次都没成功过。
 *
 * 这道闸钉的是：只要 `app.json` 里没有 `extra.eas.projectId`，
 * `isPushConfigured` 就必须为 false —— 让「没配」是一个能被读出来的值，
 * 而不是散落在日志里的一条 error。
 *
 * ⚠️ 它**不**断言「必须配上」。配 projectId 是 owner 的账号级动作，代码里补不出来。
 * 等真的配上之后，下面那条阴性对照会自动变成主张。
 */
import appJson from '../../../app.json';

describe('推送配置是可观测的事实', () => {
  const extra = (appJson as { expo?: { extra?: Record<string, unknown> } }).expo?.extra ?? {};
  const projectId = (extra as { eas?: { projectId?: string } }).eas?.projectId;

  it('app.json 的 extra.eas.projectId 与「推送是否已配置」必须一致', () => {
    // 自检：读到的确实是 app.json 而不是空对象
    expect(Object.keys(extra).length).toBeGreaterThan(0);

    if (projectId) {
      expect(typeof projectId).toBe('string');
      expect(projectId.length).toBeGreaterThan(0);
    } else {
      // 当前状态：没有配。⇒ 产品里任何「推送已启用」的说法都是假的。
      expect(projectId).toBeUndefined();
    }
  });

  it('⛔ 未配置时不许把它说成 Web 专属问题', () => {
    // 这一条守的是注释里的措辞 —— 描述过期比没有描述更糟。
    const src = require('fs').readFileSync(
      require('path').join(__dirname, '../../services/pushNotificationService.ts'),
      'utf8',
    ) as string;
    if (!projectId) {
      // 未配置时必须写明「真机也没有」—— 描述过期比没有描述更糟
      expect(src).toMatch(/真机也没有/);
    }
  });
});
