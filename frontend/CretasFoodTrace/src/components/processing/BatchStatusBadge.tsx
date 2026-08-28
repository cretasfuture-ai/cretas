import React from 'react';
import { View, StyleSheet, Text } from 'react-native';
import { Chip, Icon } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { statusKey } from '../../utils/statusKey';

/**
 * 归一化后的批次状态（camelCase）。
 *
 * 🔴 2026-08-29 逐屏走查：这里原来是 snake_case（`'in_progress'`），而后端
 * `ProductionBatchStatus` 发的是 **UPPER_SNAKE**（`IN_PROGRESS`）。
 * `switch` 于是**每一条都掉进 default** → 显示「未知」。
 * 生产库实测 `production_batches` 65 条（IN_PROGRESS 57 / COMPLETED 8）**全部**显示未知，
 * 而同一批次在列表页显示「生产中」—— 同一个东西两个屏两个答案。
 *
 * ⚠️ 后端枚举 7 个值：PLANNED / PLANNING / IN_PROGRESS / PRODUCING / PAUSED / COMPLETED / CANCELLED。
 * 原来这里缺 `planned` 与 `producing` 两个 —— 今天数据里没有，不等于代码里覆盖了。
 */
export type BatchStatus =
  | 'planned'
  | 'planning'
  | 'inProgress'
  | 'producing'
  | 'paused'
  | 'qualityCheck'
  | 'completed'
  | 'failed'
  | 'cancelled';

interface BatchStatusBadgeProps {
  status: BatchStatus;
  size?: 'small' | 'medium' | 'large';
}

/**
 * Batch Status Badge Component
 * 7 statuses with different colors and icons
 */
export const BatchStatusBadge: React.FC<BatchStatusBadgeProps> = ({
  status,
  size = 'medium',
}) => {
  const { t } = useTranslation('processing');

  const getStatusConfig = () => {
    // ⛔ 不要直接 switch(status)：后端发大写下划线，这里的分支是 camelCase。
    switch (statusKey(status)) {
      case 'planned':
        return {
          label: t('batchStatus.planned'),
          color: '#2196F3',
          backgroundColor: '#E3F2FD',
          icon: 'calendar-clock',
        };
      case 'planning':
        return {
          label: t('batchStatus.planning'),
          color: '#2196F3',
          backgroundColor: '#E3F2FD',
          icon: 'clock-outline',
        };
      case 'inProgress':
        return {
          label: t('batchStatus.inProgress'),
          color: '#FF9800',
          backgroundColor: '#FFF3E0',
          icon: 'play-circle',
        };
      case 'producing':
        return {
          label: t('batchStatus.producing'),
          color: '#FF9800',
          backgroundColor: '#FFF3E0',
          icon: 'factory',
        };
      case 'paused':
        return {
          label: t('batchStatus.paused'),
          color: '#F44336',
          backgroundColor: '#FFEBEE',
          icon: 'pause-circle',
        };
      case 'qualityCheck':
        return {
          label: t('batchStatus.qualityCheck'),
          color: '#FFC107',
          backgroundColor: '#FFF8E1',
          icon: 'shield-check',
        };
      case 'completed':
        return {
          label: t('batchStatus.completed'),
          color: '#4CAF50',
          backgroundColor: '#E8F5E9',
          icon: 'check-circle',
        };
      case 'failed':
        return {
          label: t('batchStatus.failed'),
          color: '#C62828',
          backgroundColor: '#FFCDD2',
          icon: 'close-circle',
        };
      case 'cancelled':
        return {
          label: t('batchStatus.cancelled'),
          color: '#9E9E9E',
          backgroundColor: '#F5F5F5',
          icon: 'cancel',
        };
      default:
        return {
          label: t('batchStatus.unknown'),
          color: '#757575',
          backgroundColor: '#EEEEEE',
          icon: 'help-circle',
        };
    }
  };

  const config = getStatusConfig();
  const chipSize = size === 'small' ? 'small' : size === 'large' ? 'large' : 'medium';

  return (
    <Chip
      mode="flat"
      textStyle={[
        styles.chipText,
        { color: config.color },
        size === 'small' && styles.chipTextSmall,
        size === 'large' && styles.chipTextLarge,
      ]}
      style={[
        styles.chip,
        { backgroundColor: config.backgroundColor },
      ]}
      compact={size === 'small'}
    >
      <View style={styles.chipContent}>
        <Icon source={config.icon} size={size === 'small' ? 14 : size === 'large' ? 20 : 16} color={config.color} />
        <Text style={{ marginLeft: 4, color: config.color }}>{config.label}</Text>
      </View>
    </Chip>
  );
};

const styles = StyleSheet.create({
  chip: {
    borderRadius: 16,
  },
  chipContent: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 4,
  },
  chipText: {
    fontWeight: '600',
    fontSize: 13,
  },
  chipTextSmall: {
    fontSize: 11,
  },
  chipTextLarge: {
    fontSize: 15,
  },
});

export default BatchStatusBadge;
