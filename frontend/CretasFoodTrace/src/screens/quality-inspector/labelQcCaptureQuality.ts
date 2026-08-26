export const LABEL_QC_CAPTURE_MIN_SHORT_EDGE = 960;
export const LABEL_QC_CAPTURE_MIN_LONG_EDGE = 1280;

export interface CaptureDimensions {
  width: number;
  height: number;
}

export interface CaptureMetadataGate {
  accepted: boolean;
  reason: string | null;
  shortEdge: number;
  longEdge: number;
  pixels: number;
}

export const evaluateLabelQcCaptureMetadata = ({
  width,
  height,
}: CaptureDimensions): CaptureMetadataGate => {
  const validWidth = Number.isFinite(width) ? Math.max(0, Math.floor(width)) : 0;
  const validHeight = Number.isFinite(height) ? Math.max(0, Math.floor(height)) : 0;
  const shortEdge = Math.min(validWidth, validHeight);
  const longEdge = Math.max(validWidth, validHeight);
  const pixels = validWidth * validHeight;

  if (validWidth === 0 || validHeight === 0) {
    return {
      accepted: false,
      reason: '没有读到照片尺寸，请重新拍摄',
      shortEdge,
      longEdge,
      pixels,
    };
  }

  if (
    shortEdge < LABEL_QC_CAPTURE_MIN_SHORT_EDGE
    || longEdge < LABEL_QC_CAPTURE_MIN_LONG_EDGE
  ) {
    return {
      accepted: false,
      reason: `照片只有 ${validWidth}×${validHeight}，至少需要短边 ${LABEL_QC_CAPTURE_MIN_SHORT_EDGE}、长边 ${LABEL_QC_CAPTURE_MIN_LONG_EDGE} 像素。请靠近托盘重新拍摄`,
      shortEdge,
      longEdge,
      pixels,
    };
  }

  return {
    accepted: true,
    reason: null,
    shortEdge,
    longEdge,
    pixels,
  };
};
