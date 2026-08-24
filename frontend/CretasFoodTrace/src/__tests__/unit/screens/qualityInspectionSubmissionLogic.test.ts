import {
  deriveQualityInspectionCounts,
  deriveQualityInspectionResult,
  getQualityInspectionSubmitErrorMessage,
} from '../../../screens/quality-inspector/qualityInspectionSubmission.logic';

describe('质检提交结果契约', () => {
  it.each([
    [10, 100, 10, 0, 100],
    [10, 95, 10, 0, 100],
    [10, 93, 9, 1, 90],
    [10, 69, 7, 3, 70],
    [3, 69, 2, 1, 200 / 3],
  ] as const)(
    '抽样 %s、评分 %s 计算为合格 %s、不合格 %s、合格率 %s',
    (sampleSize, score, passCount, failCount, passRate) => {
      const counts = deriveQualityInspectionCounts(sampleSize, score);
      expect(counts.passCount).toBe(passCount);
      expect(counts.failCount).toBe(failCount);
      expect(counts.passRate).toBeCloseTo(passRate, 8);
    },
  );

  it.each([
    [100, 'PASS'],
    [95, 'PASS'],
    [94.99, 'CONDITIONAL'],
    [85, 'CONDITIONAL'],
    [70, 'CONDITIONAL'],
    [69.99, 'FAIL'],
    [0, 'FAIL'],
  ] as const)('合格率 %s 映射为 %s', (passRate, expected) => {
    expect(deriveQualityInspectionResult(passRate)).toBe(expected);
  });

  it('优先显示后端完整中文拒绝原因', () => {
    const message = getQualityInspectionSubmitErrorMessage(
      {
        message: 'Request failed with status code 400',
        response: {
          data: {
            message: '合格率 70.00% 不能判为 FAIL，请选择 CONDITIONAL',
          },
        },
      },
      '请检查网络后重试',
    );

    expect(message).toBe('合格率 70.00% 不能判为 FAIL，请选择 CONDITIONAL');
  });

  it('无后端消息时保留明确回退提示', () => {
    expect(getQualityInspectionSubmitErrorMessage({}, '请检查网络后重试'))
      .toBe('请检查网络后重试');
  });
});
