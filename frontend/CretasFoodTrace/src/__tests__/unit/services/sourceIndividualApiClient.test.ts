/**
 * 守「拍照核验」客户端的**三态**: 成功 / 模型没看懂(422) / 其它错误。
 *
 * 🔴 为什么这三态必须分开(这是本改动的核心风险, 不是锦上添花):
 *   · 成功        ⇒ 屏上渲染结果卡
 *   · 422         ⇒ 「没认出来」+ 模型原文, 用户该**重拍**
 *   · 502 / 网络  ⇒ 「服务不可用」, 用户该**等一会儿或找运维**
 * 把后两者折叠成同一个「失败」, 现场会一直重拍一张永远不会成功的照片。
 *
 * ⚠️ 本仓既有 api 客户端(formAssistantApiClient)在 catch 里返回
 * `{ success: false, fieldValues: {}, confidence: 0 }` —— 那是**降级兜底**,
 * 核心原则 1 禁止。这里刻意不学它: 非 422 一律 rethrow。
 * 下面有一条断言专门钉住这件事。
 */
import { sourceIndividualApiClient } from '../../../services/api/sourceIndividualApiClient';
import { apiClient } from '../../../services/api/apiClient';

jest.mock('../../../services/api/apiClient', () => ({
  apiClient: { get: jest.fn(), post: jest.fn(), put: jest.fn(), delete: jest.fn() },
}));

jest.mock('../../../utils/factoryIdHelper', () => ({
  getCurrentFactoryId: jest.fn(() => 'F006'),
}));

const mocked = apiClient as jest.Mocked<typeof apiClient>;

/** 造一个 axios 形状的错误 —— 真实那一侧就是这个形状(拦截器原样 reject 原始 error)。 */
function axiosError(status: number, data: unknown) {
  return Object.assign(new Error(`Request failed with status code ${status}`), {
    response: { status, data },
  });
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('verify() 的三态', () => {
  it('成功 ⇒ ok:true 且结果原样带出来', async () => {
    mocked.post.mockResolvedValue({
      success: true,
      data: {
        templateCode: 'EAR_TAG',
        fields: { tagNumber: '118-0245', confidence: 0.92 },
        confidence: 0.92,
        normalizedTagNumber: '1180245',
        matchCount: 1,
        matched: { id: 'si-1', individualCode: 'SI-0001' },
      },
    } as never);

    const outcome = await sourceIndividualApiClient.verify({
      templateCode: 'EAR_TAG',
      imageBase64: 'aGk=',
    });

    expect(outcome.ok).toBe(true);
    if (!outcome.ok) throw new Error('unreachable');
    expect(outcome.result.normalizedTagNumber).toBe('1180245');
    expect(outcome.result.matchCount).toBe(1);
    expect(mocked.post).toHaveBeenCalledWith('/api/mobile/F006/vision/verify', {
      templateCode: 'EAR_TAG',
      imageBase64: 'aGk=',
    });
  });

  it('🔴 422 ⇒ ok:false 且**带上模型原文**, 不抛异常', async () => {
    const raw = '抱歉，我看不清这张图片里的内容。';
    mocked.post.mockRejectedValue(
      axiosError(422, {
        code: 422,
        message: '视觉模型返回的不是 JSON',
        data: { templateCode: 'EAR_TAG', rawText: raw, parseError: '视觉模型返回的不是 JSON' },
      })
    );

    const outcome = await sourceIndividualApiClient.verify({
      templateCode: 'EAR_TAG',
      imageBase64: 'aGk=',
    });

    expect(outcome.ok).toBe(false);
    if (outcome.ok) throw new Error('unreachable');
    // rawText 是现场唯一能据以判断「为什么没认出来」的东西 —— 丢掉它就只剩一句「失败」。
    expect(outcome.rawText).toBe(raw);
    expect(outcome.reason).toContain('JSON');
    expect(outcome.templateCode).toBe('EAR_TAG');
  });

  it('🔴 502 ⇒ **抛出去**, ⛔ 不伪装成「识别失败」', async () => {
    mocked.post.mockRejectedValue(
      axiosError(502, { code: 502, message: '视觉识别服务暂时不可用, 请稍后重试' })
    );

    await expect(
      sourceIndividualApiClient.verify({ templateCode: 'EAR_TAG', imageBase64: 'aGk=' })
    ).rejects.toThrow();
  });

  it('403 / 网络错误同样抛出去 —— 只有 422 是结构化结果', async () => {
    mocked.post.mockRejectedValue(axiosError(403, { code: 403, message: '权限不足' }));
    await expect(
      sourceIndividualApiClient.verify({ templateCode: 'MEAT_CUT', imageBase64: 'aGk=' })
    ).rejects.toThrow();

    // 连 response 都没有的网络层错误(超时/断网)也必须抛。
    mocked.post.mockRejectedValue(new Error('Network Error'));
    await expect(
      sourceIndividualApiClient.verify({ templateCode: 'MEAT_CUT', imageBase64: 'aGk=' })
    ).rejects.toThrow('Network Error');
  });
});

describe('读取类接口: 读不到就抛, ⛔ 不返回假的空结果', () => {
  it('列表响应里没有 data ⇒ 抛错, 不返回空列表', async () => {
    mocked.get.mockResolvedValue({ success: false, message: '个体档案列表读取失败' } as never);
    await expect(sourceIndividualApiClient.list()).rejects.toThrow('个体档案列表读取失败');
  });

  it('by-tag: matchCount 0 / 1 / n 原样透传 —— 三者在 UI 上是三件事', async () => {
    mocked.get.mockResolvedValue({
      success: true,
      data: { tagNumber: '1180245', matchCount: 0, matched: null, allMatches: [] },
    } as never);
    const none = await sourceIndividualApiClient.findByTag('1180245');
    expect(none.matchCount).toBe(0);
    expect(none.matched).toBeNull();

    mocked.get.mockResolvedValue({
      success: true,
      data: {
        tagNumber: '1180245',
        matchCount: 2,
        matched: { id: 'a', individualCode: 'SI-NEW' },
        allMatches: [
          { id: 'a', individualCode: 'SI-NEW' },
          { id: 'b', individualCode: 'SI-OLD' },
        ],
      },
    } as never);
    const many = await sourceIndividualApiClient.findByTag('1180245');
    // ⚠️ 客户端⛔ 不许把 >1 压成 1 「顺手选一个」—— 那正是「把我不知道翻译成一个正常值」。
    expect(many.matchCount).toBe(2);
    expect(many.allMatches).toHaveLength(2);
  });

  it('路径里的耳标号做 URL 编码', async () => {
    mocked.get.mockResolvedValue({ success: true, data: { tagNumber: 'x', matchCount: 0, matched: null, allMatches: [] } } as never);
    await sourceIndividualApiClient.findByTag('A/B#1');
    expect(mocked.get).toHaveBeenCalledWith('/api/mobile/F006/source-individuals/by-tag/A%2FB%231');
  });
});
