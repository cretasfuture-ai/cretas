import { describeSopError, formatSopSize, isRealWorkProcessId, sopKindLabel } from '../../../utils/processSop';

describe('isRealWorkProcessId', () => {
  it.each(['WP-PACK', 'wp-1234', '切片'])('真实工序 %s 要请求', (id) => {
    expect(isRealWorkProcessId(id)).toBe(true);
  });
  it.each(['__MATERIAL_INPUT__', '__FINAL_OUTPUT__', '__ANYTHING__', '', '   ', null, undefined])(
    '哨兵 / 空值 %p 不请求', (id) => {
      expect(isRealWorkProcessId(id as string | null | undefined)).toBe(false);
    },
  );
});

describe('describeSopError', () => {
  it('优先后端 message', () => {
    expect(describeSopError({ response: { data: { message: ' 无权查看 ' } }, message: 'Request failed' })).toBe('无权查看');
  });
  it('其次异常自带信息', () => {
    expect(describeSopError(new Error('Network Error'))).toBe('Network Error');
  });
  it('什么都没有也要给一句人话, 不返回空串', () => {
    expect(describeSopError(undefined)).toBe('网络异常');
    expect(describeSopError({ response: { data: { message: '  ' } }, message: '' })).toBe('网络异常');
  });
});

describe('formatSopSize / sopKindLabel', () => {
  it('大小', () => {
    expect(formatSopSize(512)).toBe('512 B');
    expect(formatSopSize(2048)).toBe('2.0 KB');
    expect(formatSopSize(5 * 1024 * 1024)).toBe('5.0 MB');
    expect(formatSopSize(undefined)).toBe('');
  });
  it('类型: PDF 看 MIME 或扩展名, 其余按图片', () => {
    expect(sopKindLabel({ fileType: 'application/pdf', fileName: 'a' })).toBe('PDF');
    expect(sopKindLabel({ fileType: '', fileName: 'a.PDF' })).toBe('PDF');
    expect(sopKindLabel({ fileType: 'image/png', fileName: 'a.png' })).toBe('图片');
  });
});
