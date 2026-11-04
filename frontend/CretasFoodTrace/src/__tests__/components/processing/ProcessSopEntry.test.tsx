import React from 'react';
import { Linking } from 'react-native';
import { act, fireEvent, render, waitFor } from '@testing-library/react-native';
import { ProcessSopEntry } from '../../../components/processing/ProcessSopEntry';
import { attachmentApi } from '../../../services/api/attachmentApi';
import { appAlert } from '../../../components/ui/AppDialog';

jest.mock('../../../services/api/attachmentApi', () => ({
  attachmentApi: { list: jest.fn(), getSignedDownloadUrl: jest.fn() },
}));
jest.mock('../../../components/ui/AppDialog', () => ({ appAlert: jest.fn() }));

const list = attachmentApi.list as jest.Mock;
const signed = attachmentApi.getSignedDownloadUrl as jest.Mock;
const alertMock = appAlert as jest.Mock;

function file(id: string, fileName: string) {
  return { id, fileName, fileType: 'application/pdf', fileSize: 1024 };
}

describe('ProcessSopEntry', () => {
  let openURL: jest.SpyInstance;
  beforeEach(() => {
    list.mockReset();
    signed.mockReset();
    alertMock.mockReset();
    openURL = jest.spyOn(Linking, 'openURL').mockResolvedValue(true as never);
  });
  afterEach(() => openURL.mockRestore());

  it.each(['__MATERIAL_INPUT__', '__FINAL_OUTPUT__', '', null, undefined])(
    '哨兵 / 空工序 %p: 不请求、不渲染任何东西', async (id) => {
      list.mockResolvedValue([file('A1', 'x.pdf')]);
      const { toJSON } = render(<ProcessSopEntry workProcessId={id as string | null | undefined} processName="切片" />);
      await Promise.resolve();
      expect(list).not.toHaveBeenCalled();
      expect(toJSON()).toBeNull();
    },
  );

  it('真实工序: 请求 WORK_PROCESS 附件, 显示数量; 没有文件时整段不渲染 (阳性对照: 上一条哨兵不请求)', async () => {
    list.mockResolvedValue([]);
    const { queryByTestId } = render(<ProcessSopEntry workProcessId="wp-1" processName="切片" />);
    await waitFor(() => expect(list).toHaveBeenCalledWith('WORK_PROCESS', 'wp-1'));
    await waitFor(() => expect(queryByTestId('process-sop-loading')).toBeNull());
    expect(queryByTestId('process-sop-entry')).toBeNull();
  });

  it('展开列表, 点文件 → 用新签地址交给系统; 签名失败 → appAlert 说清原因, 不调用系统', async () => {
    list.mockResolvedValue([file('A1', '切片标准.pdf'), file('A2', '示意图.pdf')]);
    signed.mockResolvedValueOnce('https://oss.invalid/signed').mockRejectedValueOnce(new Error('OSS 不可用'));
    const { findByText, getByTestId } = render(<ProcessSopEntry workProcessId="wp-1" processName="切片" />);
    fireEvent.press(await findByText('查看 SOP（2）'));
    fireEvent.press(getByTestId('process-sop-file-A1'));
    await waitFor(() => expect(openURL).toHaveBeenCalledWith('https://oss.invalid/signed'));

    fireEvent.press(getByTestId('process-sop-file-A2'));
    await waitFor(() => expect(alertMock).toHaveBeenCalledTimes(1));
    expect(alertMock.mock.calls[0][0]).toBe('打不开这份 SOP');
    expect(alertMock.mock.calls[0][1]).toContain('OSS 不可用');
    expect(openURL).toHaveBeenCalledTimes(1);
  });

  it('换道 (workProcessId 变化): 旧工序慢返回的结果不覆盖新工序', async () => {
    let resolveOld: (v: unknown) => void = () => undefined;
    list.mockImplementationOnce(() => new Promise((r) => { resolveOld = r; }));
    list.mockResolvedValueOnce([file('B1', '新工序.pdf')]);
    const { rerender, findByText, queryByText } = render(<ProcessSopEntry workProcessId="wp-old" processName="旧" />);
    rerender(<ProcessSopEntry workProcessId="wp-new" processName="新" />);
    expect(await findByText('查看 SOP（1）')).toBeTruthy();
    await act(async () => {
      resolveOld([file('A1', 'a.pdf'), file('A2', 'b.pdf'), file('A3', 'c.pdf')]);
      await new Promise((r) => setTimeout(r, 20));
    });
    expect(queryByText('查看 SOP（3）')).toBeNull();
    expect(queryByText('查看 SOP（1）')).toBeTruthy();
  });
});
