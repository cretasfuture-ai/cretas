/**
 * aiWriteConfirm 纯逻辑: 集成测试 (AIChatScreenWriteConfirm) 够不着的分支。
 * 每条正向配一条对照, 防恒真式。
 */
import {
  applyPreviewResponse,
  blockedPreviewReason,
  classifyConfirmError,
  classifyConfirmResponse,
  isPreviewExpired,
  previewRows,
  remainingSeconds,
  writeConfirmFromStreamResult,
  type WriteConfirmState,
} from '../aiWriteConfirm';
import { buildConfirmationProof } from '../../../services/api/intentConfigApiClient';

const ACTION = {
  confirmToken: 'tok',
  commandDigest: 'b'.repeat(64),
  expiresAt: '2099-01-01T00:00:00Z',
  expiresInSeconds: 300,
};

function preparing(): WriteConfirmState {
  return { phase: 'preparing', intentName: '新增样品', userInput: '新增样品', leadText: '', previewAttempt: 0 };
}

describe('writeConfirmFromStreamResult', () => {
  it('WRITE_CONFIRM_REQUIRED → preparing, 保留原话与意图码', () => {
    const s = writeConfirmFromStreamResult({ status: 'WRITE_CONFIRM_REQUIRED', intentCode: 'X' }, '原话');
    expect(s).toEqual(expect.objectContaining({ phase: 'preparing', intentCode: 'X', userInput: '原话', previewAttempt: 0 }));
  });
  it('流里直接来的 PREVIEW + confirmableAction → ready, 气泡用 description 而不是原始 JSON', () => {
    const s = writeConfirmFromStreamResult({
      status: 'PREVIEW', intentName: '新增样品', message: '{"success":true}',
      confirmableAction: { ...ACTION, description: '确认后将实际执行「新增样品」', previewData: { success: true, data: { a: 1 } } },
    }, '原话', 1000);
    expect(s?.phase).toBe('ready');
    expect(s?.leadText).toBe('确认后将实际执行「新增样品」');
  });
  it('对照: 其它状态 (含没有 confirmableAction 的 PREVIEW) 不出卡', () => {
    expect(writeConfirmFromStreamResult({ status: 'COMPLETED' }, 'x')).toBeUndefined();
    expect(writeConfirmFromStreamResult({ status: 'SUCCESS', message: '写成功' }, 'x')).toBeUndefined();
    expect(writeConfirmFromStreamResult({ status: 'PREVIEW' }, 'x')).toBeUndefined();
  });
});

describe('blockedPreviewReason', () => {
  it('success=false / canDo=false 被拦, 原话透出', () => {
    expect(blockedPreviewReason({ success: false, error: '参数验证失败: 数量必须 > 0' })).toBe('参数验证失败: 数量必须 > 0');
    expect(blockedPreviewReason({ success: true, data: { canDo: false, message: '已全部入库' } })).toBe('已全部入库');
  });
  it('对照: 通过的预览返回 null', () => {
    expect(blockedPreviewReason({ success: true, data: { canDo: true } })).toBeNull();
    expect(blockedPreviewReason(null)).toBeNull();
  });
});

describe('applyPreviewResponse', () => {
  it('PREVIEW 缺 token 字段 → blocked (不支持一键确认), 不给确认', () => {
    const s = applyPreviewResponse(preparing(), { status: 'PREVIEW' }, 0);
    expect(s.phase).toBe('blocked');
    expect(s.notice).toContain('请到对应功能页面手工操作');
  });
  it('NEED_MORE_INFO → asking (追问, 不是 blocked 拒绝), 内容就是问题本身', () => {
    const s = applyPreviewResponse(preparing(), { status: 'NEED_MORE_INFO', clarificationQuestions: ['样品编号是多少?'] }, 0);
    expect(s.phase).toBe('asking');
    expect(s.notice).toBe('样品编号是多少?');
    expect(s.action).toBeUndefined();
  });
  it('NEED_MORE_INFO 多个问题才编号; 没有问题列表时用后端原话', () => {
    const many = applyPreviewResponse(preparing(), { status: 'NEED_MORE_INFO', clarificationQuestions: ['哪个批次?', '领多少?'] }, 0);
    expect(many.phase).toBe('asking');
    expect(many.notice).toBe('1. 哪个批次?\n2. 领多少?');
    const said = applyPreviewResponse(preparing(), { status: 'NEED_MORE_INFO', message: '请直接说批次号。' }, 0);
    expect(said.phase).toBe('asking');
    expect(said.notice).toBe('请直接说批次号。');
  });
  it('对照: PREVIEW_BLOCKED / 权限 / 不支持 仍是 blocked 拒绝, 不会被当成追问', () => {
    const blocked = applyPreviewResponse(preparing(), { status: 'PREVIEW_BLOCKED', message: '批次已用完' }, 0);
    expect(blocked.phase).toBe('blocked');
    expect(applyPreviewResponse(preparing(), { status: 'PERMISSION_DENIED' }, 0).phase).toBe('blocked');
    expect(applyPreviewResponse(preparing(), { status: 'PREVIEW_UNSUPPORTED' }, 0).phase).toBe('blocked');
  });
  it('PREVIEW_BLOCKED (后端 #907: 预览没通过不发令牌) → blocked, 只说原因, 不拼「手工操作」', () => {
    const s = applyPreviewResponse(preparing(), {
      status: 'PREVIEW_BLOCKED', message: '这道工序当前是「待开始」，不能直接完成。',
    }, 0);
    expect(s.phase).toBe('blocked');
    expect(s.notice).toBe('这道工序当前是「待开始」，不能直接完成。');
    expect(s.notice).not.toContain('手工操作');
    expect(s.action).toBeUndefined();
  });
  it('对照: PREVIEW_UNSUPPORTED (功能不支持) 仍然带手工兜底那句', () => {
    const s = applyPreviewResponse(preparing(), { status: 'PREVIEW_UNSUPPORTED', message: '预览模式暂不支持此意图类型' }, 0);
    expect(s.notice).toContain('预览模式暂不支持此意图类型');
    expect(s.notice).toContain('请到对应功能页面手工操作');
  });
  it('对照: 完整 PREVIEW → ready 并记录本地接收时刻', () => {
    const s = applyPreviewResponse(preparing(), { status: 'PREVIEW', confirmableAction: ACTION }, 5000);
    expect(s.phase).toBe('ready');
    expect(s.receivedAtMs).toBe(5000);
  });
});

describe('倒计时', () => {
  it('按本地接收时刻 + expiresInSeconds 计, 到 0 即过期', () => {
    const s = applyPreviewResponse(preparing(), { status: 'PREVIEW', confirmableAction: ACTION }, 0);
    expect(remainingSeconds(s, 1_000)).toBe(299);
    expect(isPreviewExpired(s, 299_000)).toBe(false);
    expect(isPreviewExpired(s, 300_000)).toBe(true);
  });
  it('对照: 没有 expiresInSeconds 时不倒计时也不判过期', () => {
    const s = applyPreviewResponse(preparing(), {
      status: 'PREVIEW', confirmableAction: { ...ACTION, expiresInSeconds: null },
    }, 0);
    expect(remainingSeconds(s, 10_000_000)).toBeNull();
    expect(isPreviewExpired(s, 10_000_000)).toBe(false);
  });
});

describe('classifyConfirmResponse', () => {
  it('SUCCESS / COMPLETED → done executed', () => {
    expect(classifyConfirmResponse({ status: 'COMPLETED', message: 'ok' }, 'x')).toEqual(expect.objectContaining({ kind: 'done', executed: true, content: 'ok' }));
  });
  it('DEMO_WRITE_BLOCKED → done 但 executed=false, 标签说清没执行', () => {
    const o = classifyConfirmResponse({ status: 'DEMO_WRITE_BLOCKED', message: '演示环境不执行真实写入。' }, 'x');
    expect(o).toEqual(expect.objectContaining({ kind: 'done', executed: false, doneLabel: '未执行（演示环境）' }));
  });
  it('对照: FAILED 执行失败不给重试 (可能双写); 凭证类失败给重新生成', () => {
    const exec = classifyConfirmResponse({ status: 'FAILED', message: '执行结果状态记录失败，请勿重复提交并联系管理员' }, 'x');
    expect(exec).toEqual(expect.objectContaining({ kind: 'failed', failureKind: 'rejected', canRegenerate: false }));
    const claimed = classifyConfirmResponse({ status: 'FAILED', message: '令牌已被处理: EXECUTED' }, 'x');
    expect(claimed).toEqual(expect.objectContaining({ kind: 'failed', failureKind: 'expired', canRegenerate: true }));
  });
  it('对照: 没有 status 的空响应不是成功', () => {
    expect(classifyConfirmResponse(undefined, 'x').kind).toBe('failed');
    expect(classifyConfirmResponse({ success: true }, 'x').kind).toBe('failed');
  });
});

describe('classifyConfirmError', () => {
  it('HTTP 400 (凭证校验) → expired 可重新生成; 401 → 重新登录', () => {
    const e400 = classifyConfirmError({ response: { status: 400, data: { message: 'confirmation proof has expired' } } }, 'x');
    expect(e400).toEqual(expect.objectContaining({ failureKind: 'expired', canRegenerate: true }));
    const e401 = classifyConfirmError({ response: { status: 401 } }, 'x');
    expect(e401.kind === 'failed' && e401.notice).toContain('重新登录');
  });
  it('对照: 500 不能说「没执行」, 也不给重试', () => {
    const e500 = classifyConfirmError({ response: { status: 500 } }, '新增样品');
    expect(e500).toEqual(expect.objectContaining({ failureKind: 'rejected', canRegenerate: false }));
    expect(e500.kind === 'failed' && e500.notice).toContain('无法确认「新增样品」是否已经执行');
  });
});

describe('previewRows', () => {
  it('有 newValues 时出「当前 → 修改后」, 跳过系统键', () => {
    const rows = previewRows({ ...ACTION, currentValues: { quantity: 10, factoryId: 'F' }, newValues: { quantity: 12 } });
    expect(rows).toEqual([{ key: 'quantity', label: '数量', before: '10', value: '12', changed: true }]);
  });
  it('没有 newValues 时出 previewData 的键值: 没有 label 的英文键照原样显示 (业务行不藏, H3), 系统键 canDo 不显示', () => {
    const rows = previewRows({ ...ACTION, newValues: {}, previewData: { success: true, data: { sampleCode: 'S-1', canDo: true } } });
    expect(rows).toEqual([{ key: 'sampleCode', label: 'sampleCode', value: 'S-1' }]);
  });
  it('空值写「暂无」, 对象按「键：值」逐项 (不是 JSON), 对象里的系统键也跳过', () => {
    const rows = previewRows({
      ...ACTION,
      newValues: {},
      previewData: { data: { notes: null, supplierName: { 名称: '甲供应商', code: 'X1', factoryId: 'F' }, batchNumber: '' } },
    });
    expect(rows).toEqual([
      { key: 'notes', label: '备注', value: '暂无' },
      { key: 'supplierName', label: '供应商', value: '名称：甲供应商；code：X1' },
      { key: 'batchNumber', label: '批次号', value: '暂无' },
    ]);
    for (const r of rows) expect(r.value).not.toMatch(/^—$|[{}"]/);
  });
  it('写工具预览用中文键 (打卡 / 领用 / 质检的 currentValues/newValues): 确认卡必须显示这些行, 不能是空卡', () => {
    // 2026-10-07 organizer 审 #954: 只认 KEY_LABELS 会把中文键全藏掉, 用户在看不到改动的情况下点确认。
    const rows = previewRows({
      ...ACTION,
      currentValues: { 今天的打卡: '还没有上班打卡', internalFooId: 'x-1' },
      newValues: { 今天的打卡: '上班 14:46', 考勤记为: '迟到', internalFooId: 'x-2' },
    });
    expect(rows.map((r) => r.key)).toEqual(['今天的打卡', 'internalFooId', '考勤记为']);
    expect(rows[0]).toEqual(expect.objectContaining({ label: '今天的打卡', before: '还没有上班打卡', value: '上班 14:46', changed: true }));
    // 2026-10-08 起没有 label 的英文键也显示 (业务行不藏, H3): 照原样显示键名, 改前改后的值都在
    expect(rows[1]).toEqual(expect.objectContaining({ label: 'internalFooId', before: 'x-1', value: 'x-2', changed: true }));
    // 新增字段 (改前没有) 的「改前」写「暂无」, 不写「—」
    expect(rows[2]).toEqual(expect.objectContaining({ label: '考勤记为', before: '暂无', value: '迟到' }));
  });
});

describe('buildConfirmationProof', () => {
  it('四个 body 字段 + token; 关联 ID 符合后端校验且每次不同', () => {
    const a = buildConfirmationProof(ACTION);
    const b = buildConfirmationProof(ACTION);
    for (const id of [a.requestId, a.idempotencyKey, b.requestId]) {
      expect(id).toMatch(/^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$/);
    }
    expect(a.requestId).not.toBe(b.requestId);
    expect(a.requestId).not.toBe(a.idempotencyKey);
    expect(a).toEqual(expect.objectContaining({ confirmToken: 'tok', commandDigest: ACTION.commandDigest, expiresAt: ACTION.expiresAt }));
  });
});
