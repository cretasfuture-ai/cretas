/**
 * 🔒 状态枚举的「拼写对得上」—— 后端发大写下划线，屏上的映射表写小写驼峰。
 *
 * <h3>被修掉的形状（2026-08-29 逐屏走查实测）</h3>
 *
 * ```
 * 生产库  material_batches.status   = AVAILABLE(106) / DEPLETED(60) / USED_UP(15)
 *         production_batches.status = IN_PROGRESS(57) / COMPLETED(8)
 * 前端表  { available, reserved, depleted, expired }
 * ```
 *
 * `map[status]` 全部落空 ⇒ 文案兜底到 `|| status`（屏上直接显示英文 `AVAILABLE`），
 * 颜色兜底到灰色。**原料批次那 181 条的状态徽章从来没有工作过**，
 * 而且它不报错、不崩、不变红 —— 只有把屏打开用眼睛看才看得见。
 *
 * ⚠️ 这道闸守的是 {@link statusKey} 本身的行为，⛔ 不是「所有屏都用了它」。
 * 后者要靠走查：全仓还有 10 处小写映射表没有逐个核对过后端实际发什么
 * （见 docs/visual-acceptance 第六轮）。**不要把这道闸读成那 10 处也安全了。**
 */
import { statusKey } from '../../utils/statusKey';

describe('statusKey 归一化后端枚举', () => {
  it('大写下划线 -> 小写驼峰（这是线上真实发过来的形状）', () => {
    expect(statusKey('AVAILABLE')).toBe('available');
    expect(statusKey('USED_UP')).toBe('usedUp');
    expect(statusKey('IN_PROGRESS')).toBe('inProgress');
    expect(statusKey('PARTIAL_DELIVERED')).toBe('partialDelivered');
  });

  it('已经是小写驼峰的原样通过 —— 接口哪天改回小写也不能坏', () => {
    expect(statusKey('available')).toBe('available');
    expect(statusKey('usedUp')).toBe('usedUp');
  });

  it('null / undefined / 空串不炸，返回空串', () => {
    expect(statusKey(null)).toBe('');
    expect(statusKey(undefined)).toBe('');
    expect(statusKey('')).toBe('');
  });

  it('⛔ 阴性对照：它不该把不相干的串改成别的东西', () => {
    // 没有下划线、本来就是小写的，进出必须一致
    expect(statusKey('draft')).toBe('draft');
    // 不认识的值原样归一化后返回 —— 交给调用方兜底成「原样显示」
    expect(statusKey('SOME_NEW_STATUS')).toBe('someNewStatus');
  });

  it('阳性对照：如果归一化没生效，大写串会原样返回 —— 那正是线上的病', () => {
    // 这条是拿来读的：`AVAILABLE !== 'available'` 就是屏上显示英文的直接原因
    expect('AVAILABLE').not.toBe('available');
    expect(statusKey('AVAILABLE')).toBe('available');
  });
});
