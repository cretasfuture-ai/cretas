import {
  LABEL_QC_CAPTURE_MIN_LONG_EDGE,
  LABEL_QC_CAPTURE_MIN_SHORT_EDGE,
  evaluateLabelQcCaptureMetadata,
} from './labelQcCaptureQuality';

describe('evaluateLabelQcCaptureMetadata', () => {
  it('accepts portrait and landscape photos at the minimum dimensions', () => {
    expect(evaluateLabelQcCaptureMetadata({ width: 960, height: 1280 }).accepted).toBe(true);
    expect(evaluateLabelQcCaptureMetadata({ width: 1280, height: 960 }).accepted).toBe(true);
  });

  it('rejects a photo whose short edge is too small', () => {
    const result = evaluateLabelQcCaptureMetadata({ width: 720, height: 1920 });

    expect(result.accepted).toBe(false);
    expect(result.reason).toContain(`短边 ${LABEL_QC_CAPTURE_MIN_SHORT_EDGE}`);
    expect(result.reason).toContain(`长边 ${LABEL_QC_CAPTURE_MIN_LONG_EDGE}`);
  });

  it('fails closed when the picker does not provide usable dimensions', () => {
    expect(evaluateLabelQcCaptureMetadata({ width: 0, height: 1280 })).toEqual(
      expect.objectContaining({
        accepted: false,
        reason: '没有读到照片尺寸，请重新拍摄',
      }),
    );
    expect(evaluateLabelQcCaptureMetadata({ width: Number.NaN, height: 1280 }).accepted).toBe(false);
  });
});
