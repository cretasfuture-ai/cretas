/**
 * 测试反馈 2026-08-12:「[工厂app] 采购订单不能新建，提示 request failed code 409」。
 *
 * 「request failed code 409」是 **axios 的默认文案**, 不是后端说的话。
 * 后端那次实际回的是 (prod 日志 2026-08-12 15:53:18 逐字):
 *     业务异常: code=409, message=该供应商未启用所选物料的供应关系
 * 并且带了 actionHint「请先在供应商详情的"供应原料"中启用该物料」。
 *
 * 拦截器原样 reject 原始 error, 各屏统一读 err.message 显示 ⇒ 那两句全被丢掉。
 * 本测试守的是「后端说了话就要把话带到」, 以及「后端没说话时不要编话」。
 */
import fs from 'fs';
import path from 'path';

const source = fs.readFileSync(
  path.resolve(__dirname, '../../../services/api/apiClient.ts'),
  'utf8',
);

/**
 * 直接复刻拦截器里那段提升逻辑并跑一遍。
 * ⚠️ 复刻会漂, 所以下面另有一组源码断言钉住「产品代码里确实有这段」——
 * 两者缺一不可 (只跑复刻 = 测试在测自己)。
 */
function liftBackendMessage(error: any): any {
  const backendMessage = error.response?.data?.message;
  if (typeof backendMessage === 'string' && backendMessage.trim() !== '') {
    error.message = backendMessage;
    const hint = error.response?.data?.actionHint;
    if (typeof hint === 'string' && hint.trim() !== '') {
      error.actionHint = hint;
      error.message = `${backendMessage}\n\n${hint}`;
    }
  }
  return error;
}

describe('后端业务文案要盖过 axios 的默认文案', () => {
  it('409 带 message + actionHint 时, 用户看到的是这两句', () => {
    const err: any = new Error('Request failed with status code 409');
    err.response = {
      status: 409,
      data: {
        message: '该供应商未启用所选物料的供应关系',
        actionHint: '请先在供应商详情的“供应原料”中启用该物料',
      },
    };

    liftBackendMessage(err);

    expect(err.message).toContain('该供应商未启用所选物料的供应关系');
    expect(err.message).toContain('请先在供应商详情');
    // 🔴 这一条才是客户报的那个症状
    expect(err.message).not.toContain('Request failed with status code');
  });

  it('只有 message 没有 actionHint 时也要带到', () => {
    const err: any = new Error('Request failed with status code 409');
    err.response = { status: 409, data: { message: '只有草稿状态的订单可以编辑' } };
    liftBackendMessage(err);
    expect(err.message).toBe('只有草稿状态的订单可以编辑');
  });

  it('阴性对照: 后端没给文案时不许编 —— 保留 axios 原文案', () => {
    const err: any = new Error('Network Error');
    err.response = undefined;              // 断网 / 超时, 根本没有 response
    liftBackendMessage(err);
    expect(err.message).toBe('Network Error');

    const blank: any = new Error('Request failed with status code 500');
    blank.response = { status: 500, data: { message: '   ' } };   // 空白串不算说话
    liftBackendMessage(blank);
    expect(blank.message).toBe('Request failed with status code 500');
  });

  it('产品代码里确实有这段提升逻辑 (复刻不会替代接线)', () => {
    expect(source).toContain('error.response?.data?.message');
    expect(source).toMatch(/error\.message\s*=\s*backendMessage/);
    expect(source).toContain('actionHint');
    // 提升必须发生在 reject 之前, 否则等于没接上
    const liftAt = source.indexOf('const backendMessage');
    const rejectAt = source.lastIndexOf('return Promise.reject(error);');
    expect(liftAt).toBeGreaterThan(-1);
    expect(liftAt).toBeLessThan(rejectAt);
  });
});
