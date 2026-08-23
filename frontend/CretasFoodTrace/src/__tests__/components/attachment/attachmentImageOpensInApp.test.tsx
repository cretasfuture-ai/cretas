/**
 * 客户 2026-08-12:「[质检] 无法查看图片 —— 质检记录里的图片打不开」。
 *
 * prod 实测 (2026-08-19) 先排除了「URL 坏了」这个最像的解释:
 *   · attachments 表 entity_type=QUALITY_CHECK 共 4314 张, **全部**有 thumbnail_url
 *   · 匿名 GET 原图   -> HTTP 200, 2426598 bytes, 真 JPEG 3072x4096
 *   · 匿名 GET 缩略图 -> HTTP 200, ~8KB
 *   · 取一个**不存在**的对象 -> NoSuchKey 而不是 AccessDenied
 *     ⇒ cretas-media 是 public-read, 未签名 URL 本来就能取, **不是签名问题**
 *   · 活进程 ALIYUN_OSS_ENABLED=true
 *
 * 那么坏在「打开」这一步。原实现 `open()` 无差别走
 * `Linking.openURL(fileUrl)` —— 把用户丢给系统浏览器: 可能外跳、可能被拦、
 * 可能变成下载、也可能没有可处理的 App。任何一种在用户嘴里都是「图片打不开」。
 * 而这是给质检员/仓管用的屏幕(fool-proof-design: 不该把他们弹出当前流程)。
 *
 * 🔴 第二个缺陷更隐蔽: 打开失败时 `setError(...)` 用的是**和列表加载同一个**
 * error state, 而组件在 `if (error)` 时**整块**替换成一行红字, 且该 state
 * 只在 entityId/refreshKey 变化时才清 —— 也就是说点一次打不开的附件,
 * **整个附件列表消失且回不来**。那比「打不开」更像「图没了」。
 *
 * 断言跑在产品真实入口(渲染 AttachmentList 本体, 只桩 API 与 Linking)。
 */
import React from 'react';
import { Linking } from 'react-native';
import { fireEvent, render, screen, waitFor } from '@testing-library/react-native';
import { AttachmentList } from '../../../components/attachment/AttachmentList';
import { attachmentApi, Attachment } from '../../../services/api/attachmentApi';

jest.mock('../../../services/api/attachmentApi', () => {
  const actual = jest.requireActual('../../../services/api/attachmentApi');
  return {
    ...actual,
    attachmentApi: {
      list: jest.fn(),
      delete: jest.fn(),
    },
  };
});

const mockedApi = attachmentApi as jest.Mocked<typeof attachmentApi>;
const mockedOpenURL = Linking.openURL as jest.MockedFunction<typeof Linking.openURL>;

/** 形状取自 prod 真实行(见文件头): 原图数 MB, 缩略图另有其 URL。 */
const PHOTO: Attachment = {
  id: 'att-photo-1',
  factoryId: 'F006',
  entityType: 'QUALITY_CHECK',
  entityId: 'qc-1',
  fileName: 'qc-1.jpg',
  fileUrl: 'https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/attachments/2026/08/19/full.jpg',
  thumbnailUrl: 'https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/attachments/2026/08/19/thumbs/t.jpg',
  fileCategory: 'PHOTO',
  fileSize: 2426598,
  uploadedAt: '2026-08-19T13:57:46Z',
} as Attachment;

const DOCUMENT: Attachment = {
  id: 'att-doc-1',
  factoryId: 'F006',
  entityType: 'QUALITY_CHECK',
  entityId: 'qc-1',
  fileName: '检验报告.pdf',
  fileUrl: 'https://cretas-media.oss-cn-shanghai.aliyuncs.com/F006/attachments/2026/08/19/report.pdf',
  fileCategory: 'DOCUMENT',
  fileSize: 88000,
  uploadedAt: '2026-08-19T13:58:00Z',
} as Attachment;

beforeEach(() => {
  jest.clearAllMocks();
  mockedOpenURL.mockResolvedValue(undefined as never);
});

function renderList(data: Attachment[]) {
  mockedApi.list.mockResolvedValue(data);
  return render(<AttachmentList entityType="QUALITY_CHECK" entityId="qc-1" factoryId="F006" />);
}

describe('质检记录里的图片要能看', () => {
  it('点图片在**应用内**打开全屏预览，不把用户丢给系统浏览器', async () => {
    renderList([PHOTO]);
    await waitFor(() => expect(screen.getByTestId('attachment-open-att-photo-1')).toBeTruthy());

    // 预览默认不在场（阳性对照: 证明下面出现的那个是这次点击带出来的）
    expect(screen.queryByTestId('attachment-preview-image')).toBeNull();

    fireEvent.press(screen.getByTestId('attachment-open-att-photo-1'));

    const preview = await screen.findByTestId('attachment-preview-image');
    // 预览要用**原图**, 不是那张 8KB 缩略图 —— 否则点开还是看不清。
    expect(preview.props.source).toEqual({ uri: PHOTO.fileUrl });

    // 关键: 没有外跳。
    expect(mockedOpenURL).not.toHaveBeenCalled();
  });

  it('非图片（PDF）仍交给系统 App —— 那才是它该做的事', async () => {
    renderList([DOCUMENT]);
    await waitFor(() => expect(screen.getByTestId('attachment-open-att-doc-1')).toBeTruthy());

    fireEvent.press(screen.getByTestId('attachment-open-att-doc-1'));

    await waitFor(() => expect(mockedOpenURL).toHaveBeenCalledWith(DOCUMENT.fileUrl));
    // 文档不该走应用内看图。
    expect(screen.queryByTestId('attachment-preview-image')).toBeNull();
  });

  it('🔴 打开失败不许把整个附件列表干掉（点一次打不开就整屏消失且回不来）', async () => {
    mockedOpenURL.mockRejectedValue(new Error('no activity found'));
    renderList([DOCUMENT, PHOTO]);
    await waitFor(() => expect(screen.getByTestId('attachment-open-att-doc-1')).toBeTruthy());

    fireEvent.press(screen.getByTestId('attachment-open-att-doc-1'));
    await waitFor(() => expect(mockedOpenURL).toHaveBeenCalled());

    // 错误要说出来……
    expect(await screen.findByText(/无法打开/)).toBeTruthy();
    // ……但列表必须还在(这两行就是被守的行为)。
    expect(screen.getByTestId('attachment-open-att-doc-1')).toBeTruthy();
    expect(screen.getByTestId('attachment-open-att-photo-1')).toBeTruthy();
  });

  it('列表**加载**失败仍然整块显示错误（阴性对照：这条路径的行为不许被上一条改掉）', async () => {
    mockedApi.list.mockRejectedValue(new Error('网络不可用'));
    render(<AttachmentList entityType="QUALITY_CHECK" entityId="qc-1" factoryId="F006" />);

    expect(await screen.findByText('网络不可用')).toBeTruthy();
    expect(screen.queryByTestId('attachment-open-att-photo-1')).toBeNull();
  });
});
