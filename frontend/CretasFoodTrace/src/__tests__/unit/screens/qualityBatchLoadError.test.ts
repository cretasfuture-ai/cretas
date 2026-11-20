import {
  describeQualityBatchLoadError,
  type QualityBatchLoadErrorTexts,
} from '../../../screens/quality-inspector/qualityInspectionSubmission.logic';

const TEXTS: QualityBatchLoadErrorTexts = {
  genericTitle: '加载批次失败',
  genericMessage: '无法加载批次信息',
  noStandardTitle: '该产品还没有质检标准',
  noStandardMessage: '该产品还没有配置质检标准，请联系质量经理在网页端配置后再检验',
};

/** 形状取自真实链路: 后端 409 + apiClient 拦截器把 message 抬到 error.message 并追加 actionHint。 */
function axiosLikeError(status: number, backendMessage: string, hint?: string) {
  const error = new Error(hint ? `${backendMessage}\n\n${hint}` : backendMessage) as Error & {
    response: { status: number; data: { success: false; message: string; actionHint?: string } };
  };
  error.response = { status, data: { success: false, message: backendMessage, actionHint: hint } };
  return error;
}

describe('打开批次失败的文案 (D-S301-m-01)', () => {
  it('产品没有启用的质检标准 → 专门文案, 不是「无法加载批次信息」', () => {
    const result = describeQualityBatchLoadError(
      axiosLikeError(409, '该产品未配置启用的质检标准', '请先为产品配置并启用质检标准'),
      TEXTS,
    );
    expect(result.kind).toBe('no-standard');
    expect(result.title).toBe('该产品还没有质检标准');
    expect(result.message).toBe('该产品还没有配置质检标准，请联系质量经理在网页端配置后再检验');
    expect(result.message).not.toBe(TEXTS.genericMessage);
  });

  it('只有 error.message 带标记(没有 response)也认得出', () => {
    const result = describeQualityBatchLoadError(new Error('该产品未配置启用的质检标准'), TEXTS);
    expect(result.kind).toBe('no-standard');
  });

  it('阴性: 别的业务拒绝 → 原样展示后端 message, 不被说成没配标准', () => {
    const result = describeQualityBatchLoadError(axiosLikeError(404, '批次不存在'), TEXTS);
    expect(result.kind).toBe('backend-message');
    expect(result.message).toBe('批次不存在');
    expect(result.title).toBe(TEXTS.genericTitle);
  });

  it.each([
    ['断网', new Error('Network Error')],
    ['抛出非 Error', 'boom'],
    ['undefined', undefined],
    ['后端 message 为空白', axiosLikeError(500, '   ')],
  ])('阴性: 真正的未知失败(%s) → 通用文案', (_name, error) => {
    const result = describeQualityBatchLoadError(error, TEXTS);
    expect(result.kind).toBe('unknown');
    expect(result.title).toBe(TEXTS.genericTitle);
    expect(result.message).toBe(TEXTS.genericMessage);
  });
});
