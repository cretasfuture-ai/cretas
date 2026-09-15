import React from 'react';
import { View, TouchableOpacity, StyleSheet } from 'react-native';
import { Text, useTheme } from 'react-native-paper';
import { useTranslation } from 'react-i18next';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import type { AppTheme } from '../../theme';

interface QuickActionCardGridProps {
  userRole: string;
  isRestaurantMode?: boolean;
  /**
   * 当前租户 factoryId。以 `CATTLE_` 开头的租户(胜龙牛业演示 `CATTLE_3701_001`)
   * 用牛业卡组, 其它租户按角色走原卡组。⛔ 不要从这里推断行业之外的东西。
   */
  factoryId?: string;
  onSendIntent: (text: string) => void;
  onNavigate: (screen: string, params?: Record<string, unknown>) => void;
}

interface CardConfig {
  icon: keyof typeof MaterialCommunityIcons.glyphMap;
  label: string;
  description: string;
  action: () => void;
}

type CardFactory = (props: QuickActionCardGridProps, t: (key: string) => string) => CardConfig[];

/** 牛业租户判定: 只认 factoryId 前缀, 与角色无关。 */
export function isCattleFactory(factoryId: string | undefined | null): boolean {
  return typeof factoryId === 'string' && factoryId.startsWith('CATTLE_');
}

/**
 * 「这头牛的后代」的落点: 只读源头个体列表 (#822 起在 BossNavigator OverviewStack /
 * ProcessingStack 都注册了, params: undefined)。老板从列表点进个体档案再看后代。
 */
export const CATTLE_OFFSPRING_ROUTE = 'SourceIndividualList';

/**
 * 牛业卡组(2026-09-16, 胜龙演示第五幕)。
 * · 「这批来自哪几头牛」⛔ 不写死批次号 —— 落到溯源查询入口让老板自己输批次。
 * · 「谁 DNA 不匹配」走问答, 由后端 Tool 决定正文格式(每头牛一行)。
 */
const CATTLE_CARDS: CardFactory = ({ onSendIntent, onNavigate }, t) => [
  {
    icon: 'cow',
    label: t('aiChat.cattleCards.batchOrigin'),
    description: t('aiChat.cattleCards.batchOriginDesc'),
    action: () => onNavigate('Traceability'),
  },
  {
    icon: 'family-tree',
    label: t('aiChat.cattleCards.offspring'),
    description: t('aiChat.cattleCards.offspringDesc'),
    action: () => onNavigate(CATTLE_OFFSPRING_ROUTE),
  },
  {
    icon: 'dna',
    label: t('aiChat.cattleCards.dnaMismatch'),
    description: t('aiChat.cattleCards.dnaMismatchDesc'),
    action: () => onSendIntent(t('aiChat.cattleCards.dnaMismatchIntent')),
  },
  {
    icon: 'camera-outline',
    label: t('aiChat.cattleCards.earTag'),
    description: t('aiChat.cattleCards.earTagDesc'),
    action: () => onNavigate('VisionVerify'),
  },
];

// 存量卡组: 文案是中文字面量(2026-01 建组时就是), 本轮只去色值, 不改文案载体。
const RESTAURANT_CARDS: CardFactory = ({ onSendIntent }) => [
  { icon: 'cash-register', label: '今日经营', description: '营收、订单与客单价', action: () => onSendIntent('对比今天与昨天的营收、订单量和客单价，说明变化、数据依据和需要关注的问题') },
  { icon: 'store-search', label: '门店对比', description: '找出异常门店', action: () => onSendIntent('比较最近7天各门店的营收、订单量和客单价，找出异常门店并说明依据') },
  { icon: 'food', label: '菜品表现', description: '销量与毛利分析', action: () => onSendIntent('分析最近7天菜品销量和毛利表现，指出最值得关注的菜品并给出可执行建议') },
  { icon: 'chart-donut', label: '损耗分析', description: '定位食材损耗', action: () => onSendIntent('分析本月食材损耗，按食材和门店定位主要问题，并给出今天可以执行的改进动作') },
  { icon: 'package-variant', label: '库存风险', description: '缺货与积压预警', action: () => onSendIntent('分析当前食材库存风险，列出可能缺货或积压的食材、影响和处理建议') },
  { icon: 'chef-hat', label: '经营建议', description: '让 AI 综合诊断', action: () => onSendIntent('基于最近7天经营数据做一次综合诊断，按优先级给出三条有数据依据的行动建议') },
];

const ROLE_CARDS: Record<string, CardFactory> = {
  factory_super_admin: ({ onSendIntent, onNavigate }) => [
    { icon: 'clipboard-text', label: '生产报工', description: '记录产量和用料', action: () => onSendIntent('我要报工') },
    { icon: 'package-down', label: '原料入库', description: '扫码或手动入库', action: () => onSendIntent('原料入库') },
    { icon: 'magnify', label: '查库存', description: '查看物料库存', action: () => onSendIntent('查看库存') },
    { icon: 'chart-bar', label: 'BOM达成率', description: '投入产出对比', action: () => onNavigate('BomAchievement') },
    { icon: 'currency-cny', label: '成本分析', description: '查看生产成本', action: () => onSendIntent('查看成本') },
    { icon: 'trending-up', label: '毛利率', description: 'SKU盈利分析', action: () => onSendIntent('查毛利率') },
  ],
  workshop_supervisor: ({ onSendIntent, onNavigate }) => [
    { icon: 'clipboard-text', label: '生产报工', description: '记录产量', action: () => onSendIntent('我要报工') },
    { icon: 'format-list-checks', label: '我的任务', description: '查看工序任务', action: () => onNavigate('ProcessTaskList') },
    { icon: 'account-group', label: '人员状态', description: '查看在岗人员', action: () => onSendIntent('查看人员') },
    { icon: 'wrench', label: '设备状态', description: '查看设备运行', action: () => onSendIntent('查看设备') },
  ],
  warehouse_manager: ({ onSendIntent, onNavigate }) => [
    // 原本指向 MaterialReceiptAI —— 那个屏提交必 409(普通批次页面已关闭无来源入库与续入),
    // 而且它在工厂侧的 ProcessingStack 里, 仓储角色的导航树里压根没有这个路由。
    // 仓储树里正规的入口是「待收货」列表 WHPurchaseReceiveList(无参)。
    // ⚠️ 不能用 WHUnorderedInboundReceive: 它必须带 noticeId, 不是空白新建。
    { icon: 'package-down', label: '原料入库', description: '扫码或手动入库', action: () => onNavigate('WHPurchaseReceiveList') },
    { icon: 'package-up', label: '出库发货', description: '发货出库', action: () => onSendIntent('出库') },
    { icon: 'magnify', label: '查库存', description: '查看物料库存', action: () => onSendIntent('查看库存') },
    { icon: 'alert', label: '低库存', description: '库存预警查看', action: () => onSendIntent('低库存预警') },
  ],
};

/** 卡组选择: 餐饮 > 牛业 > 角色。导出给测试钉「同一个 factoryId 落到同一组」。 */
export function resolveCardFactory(props: Pick<QuickActionCardGridProps, 'userRole' | 'isRestaurantMode' | 'factoryId'>): CardFactory | undefined {
  if (props.isRestaurantMode) return RESTAURANT_CARDS;
  if (isCattleFactory(props.factoryId)) return CATTLE_CARDS;
  return ROLE_CARDS[props.userRole];
}

const QuickActionCardGrid: React.FC<QuickActionCardGridProps> = (props) => {
  const theme = useTheme<AppTheme>();
  const { t } = useTranslation('home');
  const configFn = resolveCardFactory(props);
  if (!configFn) return null;

  const cards = configFn(props, t);
  // 颜色层: 全部卡片同一族(primaryContainer), 不再每卡一色。
  const cardTone = {
    backgroundColor: theme.colors.surface,
    borderColor: theme.colors.outlineVariant,
    borderRadius: theme.custom.borderRadius.l,
    ...theme.custom.shadows.small,
  };
  const iconTone = {
    backgroundColor: theme.colors.primaryContainer,
    borderRadius: theme.custom.borderRadius.l,
  };

  return (
    <View testID="quick-action-card-grid" style={[styles.grid, { gap: theme.custom.spacing.m }]}>
      {cards.map((card, index) => (
        <TouchableOpacity
          key={index}
          testID={`quick-action-card-${index}`}
          accessibilityRole="button"
          accessibilityLabel={card.label}
          accessibilityHint={card.description}
          style={[styles.card, cardTone]}
          activeOpacity={0.7}
          onPress={card.action}
        >
          <View testID={`quick-action-icon-${index}`} style={[styles.iconContainer, iconTone]}>
            <MaterialCommunityIcons name={card.icon} size={36} color={theme.colors.onPrimaryContainer} />
          </View>
          <Text
            testID={`quick-action-label-${index}`}
            variant="titleMedium"
            style={[styles.label, { color: theme.colors.onSurface }]}
            numberOfLines={1}
          >
            {card.label}
          </Text>
          <Text
            variant="bodyMedium"
            style={[styles.description, { color: theme.colors.onSurfaceVariant }]}
            numberOfLines={2}
          >
            {card.description}
          </Text>
        </TouchableOpacity>
      ))}
    </View>
  );
};

// ⚠️ 这里只放布局: 颜色 / 字号全部在 render 里走 useTheme() 与 Paper 的 variant。
const styles = StyleSheet.create({
  grid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
  },
  card: {
    flex: 1,
    minWidth: '45%',
    maxWidth: '50%',
    height: 160,
    borderWidth: 1,
    paddingVertical: 20,
    paddingHorizontal: 16,
    alignItems: 'center',
    justifyContent: 'center',
  },
  iconContainer: {
    width: 64,
    height: 64,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: 12,
  },
  label: {
    fontWeight: '700',
    marginBottom: 4,
  },
  description: {
    textAlign: 'center',
  },
});

export { QuickActionCardGrid };
export default QuickActionCardGrid;
