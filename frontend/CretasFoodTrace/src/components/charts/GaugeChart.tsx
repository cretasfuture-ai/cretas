import React, { useMemo } from 'react';
import { View, StyleSheet, Text } from 'react-native';
import Svg, { Path, Circle, G, Line, Text as SvgText } from 'react-native-svg';

/**
 * GaugeChart Props Interface
 */
export interface GaugeChartProps {
  /** Current value (0-100 by default) */
  value: number;
  /** Maximum value (default: 100) */
  maxValue?: number;
  /** Chart title displayed at bottom */
  title?: string;
  /** Unit displayed after value (e.g., '%', 'kg') */
  unit?: string;
  /** Primary color for the gauge */
  color?: string;
  /** Size of the chart in pixels (default: 200) */
  size?: number;
  /** Threshold values for color zones */
  thresholds?: {
    /** Warning threshold (yellow zone starts) */
    warning: number;
    /** Danger threshold (red zone starts) */
    danger: number;
  };
  /** Show scale ticks (default: true) */
  showTicks?: boolean;
  /** Show scale labels (default: true) */
  showLabels?: boolean;
}

/**
 * Default threshold values
 */
const DEFAULT_THRESHOLDS = {
  warning: 60,
  danger: 80,
};

/**
 * Default colors for zones
 */
const ZONE_COLORS = {
  safe: '#4CAF50',      // Green
  warning: '#FF9800',   // Orange/Yellow
  danger: '#F44336',    // Red
  background: '#E0E0E0', // Light gray
  needle: '#212121',    // Dark gray
  center: '#FFFFFF',    // White
  text: '#212121',      // Dark text
  labelText: '#757575', // Gray text
};

/**
 * 极坐标 -> 直角坐标。角度沿用表盘约定：**180° 在左(9 点)，0° 在右(3 点)，90° 在正上**。
 *
 * 🔴 2026-08-29 逐屏走查发现: 原实现是
 *
 *     const angleInRadians = ((angleInDegrees - 180) * Math.PI) / 180;
 *     x: cx + r * Math.cos(...)   y: cy + r * Math.sin(...)
 *
 * 代入 180°(本该是左) 得到 (cx + r, cy) —— **右边**。整张表盘水平镜像了：
 * 0 画在右端、满值画在左端，刻度「80」落在左边、「20」落在右边。
 * 弧、指针、刻度都走这一个函数, 所以三者**自洽地一起反着**, 看起来不像坏 ——
 * 只有把刻度数字读出来才发现。实拍: KPI 屏「综合评分 9.3 分 / 评级 D」而指针
 * 停在右侧绿区。
 *
 * ⚠️ 代码当时和自己的注释是矛盾的(`const startAngle = 180; // Left side (9 o'clock)`),
 *    而那行注释是对的 —— 错的是这个换算。
 *
 * 提到模块级并导出, 是为了让闸能直接量几何, ⛔ 不用去渲染组件再猜像素。
 */
export function polarToCartesian(
  cx: number,
  cy: number,
  r: number,
  angleInDegrees: number
): { x: number; y: number } {
  const angleInRadians = (angleInDegrees * Math.PI) / 180;
  return {
    x: cx + r * Math.cos(angleInRadians),
    // SVG 的 y 轴向下, 所以正上方要减
    y: cy - r * Math.sin(angleInRadians),
  };
}

/**
 * GaugeChart Component
 *
 * A half-circle gauge chart that displays a value with color-coded zones.
 * Supports custom thresholds for warning and danger levels.
 *
 * @example
 * ```tsx
 * <GaugeChart
 *   value={75}
 *   maxValue={100}
 *   title="CPU Usage"
 *   unit="%"
 *   thresholds={{ warning: 60, danger: 80 }}
 * />
 * ```
 */
export function GaugeChart({
  value,
  maxValue = 100,
  title,
  unit = '',
  color,
  size = 200,
  thresholds = DEFAULT_THRESHOLDS,
  showTicks = true,
  showLabels = true,
}: GaugeChartProps): React.ReactElement {
  // Normalize value to be within bounds
  const normalizedValue = Math.max(0, Math.min(value, maxValue));
  const percentage = (normalizedValue / maxValue) * 100;

  // Calculate dimensions
  const strokeWidth = size * 0.12;
  const radius = (size - strokeWidth) / 2;
  const centerX = size / 2;
  const centerY = size / 2;

  // Arc configuration (180 degrees, from left to right)
  const startAngle = 180; // Left side (9 o'clock)
  const endAngle = 0;     // Right side (3 o'clock)
  const arcSpan = 180;    // Total arc span in degrees

  /**
   * Generate arc path
   */
  const describeArc = (
    cx: number,
    cy: number,
    r: number,
    startAngleDeg: number,
    endAngleDeg: number
  ): string => {
    const start = polarToCartesian(cx, cy, r, startAngleDeg);
    const end = polarToCartesian(cx, cy, r, endAngleDeg);
    const largeArcFlag = Math.abs(endAngleDeg - startAngleDeg) > 180 ? 1 : 0;
    // 角度从 180 递减到 0 = 从左经上到右 = SVG 里的**顺时针**(sweep=1)。
    // 修正镜像之后, 原来的判断方向也跟着反了。
    const sweepFlag = endAngleDeg < startAngleDeg ? 1 : 0;

    return [
      'M', start.x, start.y,
      'A', r, r, 0, largeArcFlag, sweepFlag, end.x, end.y,
    ].join(' ');
  };

  /**
   * Calculate arc paths for each zone
   */
  const arcPaths = useMemo(() => {
    const { warning, danger } = thresholds;

    // Convert threshold percentages to angles
    const warningAngle = startAngle - (warning / 100) * arcSpan;
    const dangerAngle = startAngle - (danger / 100) * arcSpan;
    const valueAngle = startAngle - (percentage / 100) * arcSpan;

    // Background arc (full)
    const backgroundArc = describeArc(centerX, centerY, radius, startAngle, endAngle);

    // Zone arcs
    const safeArc = describeArc(centerX, centerY, radius, startAngle, warningAngle);
    const warningArc = describeArc(centerX, centerY, radius, warningAngle, dangerAngle);
    const dangerArc = describeArc(centerX, centerY, radius, dangerAngle, endAngle);

    // Value arc (filled portion)
    const valueArc = describeArc(centerX, centerY, radius, startAngle, valueAngle);

    return {
      backgroundArc,
      safeArc,
      warningArc,
      dangerArc,
      valueArc,
      valueAngle,
    };
  }, [percentage, thresholds, centerX, centerY, radius]);

  /**
   * Determine current zone color based on value
   */
  const currentZoneColor = useMemo(() => {
    if (color) return color;

    if (percentage >= thresholds.danger) {
      return ZONE_COLORS.danger;
    } else if (percentage >= thresholds.warning) {
      return ZONE_COLORS.warning;
    }
    return ZONE_COLORS.safe;
  }, [percentage, thresholds, color]);

  /**
   * Calculate needle position
   */
  const needleProps = useMemo(() => {
    const needleLength = radius * 0.75;
    const needleAngle = arcPaths.valueAngle;
    const needleEnd = polarToCartesian(centerX, centerY, needleLength, needleAngle);

    return {
      x1: centerX,
      y1: centerY,
      x2: needleEnd.x,
      y2: needleEnd.y,
    };
  }, [arcPaths.valueAngle, centerX, centerY, radius]);

  /**
   * Generate tick marks
   */
  const tickMarks = useMemo(() => {
    if (!showTicks) return [];

    const ticks: Array<{
      x1: number;
      y1: number;
      x2: number;
      y2: number;
      label?: string;
      labelX?: number;
      labelY?: number;
    }> = [];

    const tickCount = 10;
    const innerRadius = radius - strokeWidth / 2 - 4;
    const outerRadius = radius + strokeWidth / 2 + 4;
    const labelRadius = radius + strokeWidth / 2 + 16;

    for (let i = 0; i <= tickCount; i++) {
      const tickValue = (i / tickCount) * maxValue;
      const tickAngle = startAngle - (i / tickCount) * arcSpan;

      const inner = polarToCartesian(centerX, centerY, innerRadius, tickAngle);
      const outer = polarToCartesian(centerX, centerY, outerRadius, tickAngle);
      const labelPos = polarToCartesian(centerX, centerY, labelRadius, tickAngle);

      const tick: typeof ticks[0] = {
        x1: inner.x,
        y1: inner.y,
        x2: outer.x,
        y2: outer.y,
      };

      // Add labels for major ticks (0, 25, 50, 75, 100)
      if (showLabels && i % 2 === 0) {
        tick.label = Math.round(tickValue).toString();
        tick.labelX = labelPos.x;
        tick.labelY = labelPos.y;
      }

      ticks.push(tick);
    }

    return ticks;
  }, [showTicks, showLabels, maxValue, centerX, centerY, radius, strokeWidth]);

  // Calculate height (half circle + space for title)
  const svgHeight = size / 2 + strokeWidth + (showLabels ? 24 : 8);

  return (
    <View style={[styles.container, { width: size }]}>
      <Svg width={size} height={svgHeight} viewBox={`0 0 ${size} ${svgHeight}`}>
        <G>
          {/* Background arc */}
          <Path
            d={arcPaths.backgroundArc}
            fill="none"
            stroke={ZONE_COLORS.background}
            strokeWidth={strokeWidth}
            strokeLinecap="round"
          />

          {/* Safe zone (green) */}
          <Path
            d={arcPaths.safeArc}
            fill="none"
            stroke={ZONE_COLORS.safe}
            strokeWidth={strokeWidth}
            strokeLinecap="round"
            opacity={0.3}
          />

          {/* Warning zone (yellow/orange) */}
          <Path
            d={arcPaths.warningArc}
            fill="none"
            stroke={ZONE_COLORS.warning}
            strokeWidth={strokeWidth}
            opacity={0.3}
          />

          {/* Danger zone (red) */}
          <Path
            d={arcPaths.dangerArc}
            fill="none"
            stroke={ZONE_COLORS.danger}
            strokeWidth={strokeWidth}
            strokeLinecap="round"
            opacity={0.3}
          />

          {/* Value arc (filled portion) */}
          <Path
            d={arcPaths.valueArc}
            fill="none"
            stroke={currentZoneColor}
            strokeWidth={strokeWidth}
            strokeLinecap="round"
          />

          {/* Tick marks */}
          {tickMarks.map((tick, index) => (
            <G key={`tick-${index}`}>
              <Line
                x1={tick.x1}
                y1={tick.y1}
                x2={tick.x2}
                y2={tick.y2}
                stroke={ZONE_COLORS.labelText}
                strokeWidth={index % 2 === 0 ? 2 : 1}
              />
              {tick.label && (
                <SvgText
                  x={tick.labelX}
                  y={tick.labelY}
                  fill={ZONE_COLORS.labelText}
                  fontSize={size * 0.05}
                  textAnchor="middle"
                  alignmentBaseline="middle"
                >
                  {tick.label}
                </SvgText>
              )}
            </G>
          ))}

          {/* Needle */}
          <Line
            x1={needleProps.x1}
            y1={needleProps.y1}
            x2={needleProps.x2}
            y2={needleProps.y2}
            stroke={ZONE_COLORS.needle}
            strokeWidth={3}
            strokeLinecap="round"
          />

          {/* Center circle */}
          <Circle
            cx={centerX}
            cy={centerY}
            r={size * 0.06}
            fill={ZONE_COLORS.center}
            stroke={ZONE_COLORS.needle}
            strokeWidth={2}
          />

          {/* Inner center dot */}
          <Circle
            cx={centerX}
            cy={centerY}
            r={size * 0.025}
            fill={currentZoneColor}
          />
        </G>
      </Svg>

      {/* Value display */}
      <View style={[styles.valueContainer, { top: size * 0.35 }]}>
        <Text
          style={[
            styles.valueText,
            { fontSize: size * 0.16, color: currentZoneColor },
          ]}
        >
          {normalizedValue.toFixed(normalizedValue % 1 === 0 ? 0 : 1)}
        </Text>
        {unit && (
          <Text
            style={[
              styles.unitText,
              { fontSize: size * 0.08, color: ZONE_COLORS.labelText },
            ]}
          >
            {unit}
          </Text>
        )}
      </View>

      {/* Title */}
      {title && (
        <Text
          style={[
            styles.titleText,
            { fontSize: size * 0.07, marginTop: -size * 0.05 },
          ]}
          numberOfLines={1}
        >
          {title}
        </Text>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    alignItems: 'center',
    justifyContent: 'center',
  },
  valueContainer: {
    position: 'absolute',
    alignItems: 'center',
    justifyContent: 'center',
    flexDirection: 'row',
  },
  valueText: {
    fontWeight: '700',
  },
  unitText: {
    fontWeight: '500',
    marginLeft: 2,
  },
  titleText: {
    color: ZONE_COLORS.text,
    fontWeight: '600',
    textAlign: 'center',
    marginTop: 8,
  },
});

export default GaugeChart;
