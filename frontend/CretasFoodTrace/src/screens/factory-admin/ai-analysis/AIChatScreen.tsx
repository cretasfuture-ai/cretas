/**
 * AI 对话屏幕
 *
 * 独立的AI聊天界面，不依赖预先的sessionId
 * - 显示欢迎消息和快捷问题
 * - 用户可直接输入问题
 * - 第一条消息时创建新会话
 * - 后续消息复用同一会话
 */

import React, { useState, useRef, useCallback, useEffect, useMemo } from 'react';
import {
  View,
  StyleSheet,
  ScrollView,
  TextInput,
  TouchableOpacity,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  Animated,
  AccessibilityInfo,
  useWindowDimensions,
  type StyleProp,
  type ViewStyle,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useRoute, RouteProp, CommonActions } from '@react-navigation/native';
import { Text, useTheme, PaperProvider } from 'react-native-paper';
import type { AppTheme } from '../../../theme';
import { useTranslation } from 'react-i18next';
import i18next from 'i18next';
import { aiService, detectAnalysisMode } from '../../../services/ai';
import type { AnalysisMode } from '../../../services/ai/types';
import { AIModeIndicator } from '../../../components/ai/AIModeIndicator';
import { FeedbackWidget, FoodKBQueryMetadata } from '../../../components/ai/FeedbackWidget';
import { RichContentRenderer, detectRichData, type RichData } from '../../../components/ai/RichContentRenderer';
import { useAuthStore } from '../../../store/authStore';
import { VoiceMicButton } from '../../../components/common/VoiceMicButton';
import { MarkdownRenderer } from '../../../components/common/MarkdownRenderer';
import { aiApiClient } from '../../../services/api/aiApiClient';
import type { IntentSSECallbacks } from '../../../services/api/aiApiClient';
import { intentConfigApiClient, buildConfirmationProof } from '../../../services/api/intentConfigApiClient';
import { AIWriteConfirmCard } from '../../../components/ai/AIWriteConfirmCard';
import {
  applyPreviewResponse,
  classifyConfirmError,
  classifyConfirmResponse,
  isPreviewExpired,
  previewRequestFailed,
  writeConfirmFromStreamResult,
  CANCELLED_REPLY,
  EXPIRED_NOTICE,
  type ConfirmOutcome,
  type WriteConfirmState,
} from '../../../components/ai/aiWriteConfirm';
import type { FAAIStackParamList } from '../../../types/navigation';
import { QuickActionCardGrid, resolveCardFactory } from '../../../components/ai/QuickActionCardGrid';
import { feedbackSounds } from '../../../services/audio/feedbackSounds';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  RESTAURANT_AGENT_RUN_ROUTE,
} from '../../../types/restaurantAgentRun';
import { RestaurantGrossMarginRunCard } from '../../../components/ai/RestaurantGrossMarginRunCard';
import { AI_DEBUG_TELEMETRY, chatFontFamily, withChatFonts } from '../../../components/ai/chatTypography';
import { isRestaurant } from '../../../utils/factoryType';
import { currentShanghaiIsoDate } from '../../../utils/shanghaiDate';
import { appAlert } from '../../../components/ui/AppDialog';

export const AI_CHAT_TIMEOUT_MS = 30_000;
const AUTO_SCROLL_THRESHOLD = 120;

// 建议操作类型
interface SuggestedAction {
  label: string;
  value: string;
  description?: string;
  params?: Record<string, unknown>;
  data?: Record<string, unknown>;
}

interface FollowUpSuggestion {
  label: string;
  question: string;
  intentCode?: string;
}

function mapFollowUps(raw: unknown): FollowUpSuggestion[] {
  if (!Array.isArray(raw)) return [];
  return raw.map((item) => {
    if (typeof item === 'string') return { label: item, question: item };
    if (!item || typeof item !== 'object' || Array.isArray(item)) return undefined;
    const candidate = item as Record<string, unknown>;
    const question = typeof candidate.question === 'string' ? candidate.question.trim() : '';
    const label = typeof candidate.label === 'string' ? candidate.label.trim() : '';
    const intentCode = typeof candidate.intentCode === 'string' ? candidate.intentCode.trim() : undefined;
    return question && label ? { label, question, intentCode } : undefined;
  }).filter((item): item is FollowUpSuggestion => item !== undefined);
}

interface RestaurantAgentLaunch {
  schemaVersion: '1.0';
  routeCode: typeof RESTAURANT_AGENT_RUN_ROUTE;
  startDate: string;
  endDate: string;
  startEndpoint: string;
  autoStart: true;
}

function isRealIsoDate(value: string): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!match) return false;
  const year = Number(match[1]);
  const month = Number(match[2]);
  const day = Number(match[3]);
  const parsed = new Date(Date.UTC(year, month - 1, day));
  return parsed.getUTCFullYear() === year
    && parsed.getUTCMonth() === month - 1
    && parsed.getUTCDate() === day;
}

function parseRestaurantAgentLaunch(
  value: unknown,
  factoryId: string,
): RestaurantAgentLaunch | undefined {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
  const candidate = value as Record<string, unknown>;
  const expectedEndpoint = `/api/mobile/${encodeURIComponent(factoryId)}/restaurant-agent/runs`;
  const today = currentShanghaiIsoDate();
  const currentMonth = today.slice(0, 7);
  if (
    candidate.schemaVersion !== '1.0'
    || candidate.routeCode !== RESTAURANT_AGENT_RUN_ROUTE
    || candidate.autoStart !== true
    || candidate.startEndpoint !== expectedEndpoint
    || typeof candidate.startDate !== 'string'
    || typeof candidate.endDate !== 'string'
    || !isRealIsoDate(candidate.startDate)
    || !isRealIsoDate(candidate.endDate)
    || candidate.startDate !== `${currentMonth}-01`
    || !candidate.endDate.startsWith(`${currentMonth}-`)
    || candidate.endDate > today
    || candidate.endDate < candidate.startDate
  ) return undefined;
  return candidate as unknown as RestaurantAgentLaunch;
}

/** 随请求带上的最近几轮对话: 只为让「好了」「就是六十箱」这种话能被看懂, 不是完整会话。 */
const MAX_RECENT_TURNS = 4;
const MAX_TURN_CHARS = 200;

export function buildRecentTurns(
  history: ReadonlyArray<{ role: string; content: string; isLoading?: boolean }>,
): Array<{ role: 'user' | 'assistant'; content: string }> {
  const turns: Array<{ role: 'user' | 'assistant'; content: string }> = [];
  for (const msg of history) {
    if ((msg.role !== 'user' && msg.role !== 'assistant') || msg.isLoading) continue;
    const content = (msg.content || '').replace(/\s+/g, ' ').trim().slice(0, MAX_TURN_CHARS);
    if (content) turns.push({ role: msg.role, content });
  }
  return turns.slice(-MAX_RECENT_TURNS);
}

/**
 * 服务端在工具执行成功后给的「上一步做了什么」(metadata.lastAction)。下一句原样带回去,
 * 「好了」「下一个」「再加十公斤」「批了吧」才能被确定性地解读成一个确认按钮 (服务端不会直接执行)。
 * 新鲜度按本机收到它的时间算 (不用服务端 at, 避开手机与服务器的时钟差)。
 */
export const LAST_ACTION_MAX_AGE_MS = 10 * 60 * 1000;

export type HeldLastAction = { value: Record<string, unknown>; receivedAt: number };

export const readLastAction = (metadata: unknown): Record<string, unknown> | undefined => {
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) return undefined;
  const raw = (metadata as Record<string, unknown>).lastAction;
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return undefined;
  const candidate = raw as Record<string, unknown>;
  if (typeof candidate.intentCode !== 'string' || !candidate.intentCode) return undefined;
  return candidate;
};

export const pickFreshLastAction = (
  held: HeldLastAction | null,
  nowMs: number,
): Record<string, unknown> | undefined => {
  if (!held) return undefined;
  const age = nowMs - held.receivedAt;
  return age >= 0 && age <= LAST_ACTION_MAX_AGE_MS ? held.value : undefined;
};

// 消息类型
interface Message {
  id: string;
  role: 'user' | 'assistant' | 'system';
  content: string;
  timestamp: Date;
  isLoading?: boolean;
  /** AI 分析模式 (仅 assistant 消息) */
  mode?: AnalysisMode;
  /** 响应时间 (仅 assistant 消息) */
  responseTimeMs?: number;
  /** 需要澄清时的建议操作列表 */
  suggestedActions?: SuggestedAction[];
  /** 后端明确标记的下一步问题；澄清候选不复用此字段。 */
  followUps?: FollowUpSuggestion[];
  /** 会话ID (用于多轮对话) */
  sessionIdForReply?: string;
  /** 意图类别（用于判断是否显示反馈组件） */
  intentCategory?: string;
  /** 意图代码（用于反馈追溯） */
  intentCode?: string;
  /** 用户原始问题（用于反馈组件） */
  userQuestion?: string;
  /** 食品知识库查询元数据（用于反馈组件） */
  foodKbMetadata?: FoodKBQueryMetadata;
  /** 结构化数据（用于富组件渲染：表格/卡片/统计） */
  richData?: RichData;
  /** 服务端授权并选择的有界餐饮分析启动说明 */
  agentRun?: RestaurantAgentLaunch;
  /** Bind launch metadata to the authenticated UI identity that received it. */
  agentRunFactoryId?: string;
  agentRunOwnerUserId?: string;
  /** 写操作确认卡 (W0 写闸拦下的写意图: 预览 → 确认执行 / 取消) */
  writeConfirm?: WriteConfirmState;
  /** 可独立呈现的可恢复错误状态。 */
  error?: boolean;
  /** 服务端回的 result.status (仅流式回答); 澄清 / 追问 / 对话继续不算「完成的回答」, 不出反馈 (标准 F4) */
  resultStatus?: string;
}

/** 还在等用户下一步的状态: 这样的消息不是「完成的回答」, 不挂 👍/👎 */
const PENDING_RESULT_STATUSES = ['NEED_CLARIFICATION', 'NEED_MORE_INFO', 'CONVERSATION_CONTINUE', 'ACTIVE'];
export function isPendingStatus(status: string | undefined): boolean {
  return status !== undefined && PENDING_RESULT_STATUSES.includes(status);
}

type AIChatRouteProp = RouteProp<FAAIStackParamList, 'AIChat'>;

// U-NAV-1 (Sprint 2 Track G): 工作流节点进入 AIChat 的 contextual greeting 构造.
// entryContext 由 WorkflowVisualizer 节点长按 / AI 入口按钮传入.
const WORKFLOW_MODULE_LABEL: Record<string, string> = {
  sales: '销售订单',
  purchase: '采购订单',
  production: '生产计划',
  finance: '财务',
  inventory: '库存',
};
const WORKFLOW_NODE_LABEL: Record<string, string> = {
  pending: '待处理',
  in_progress: '进行中',
  done: '已完成',
};
function buildWorkflowGreeting(ctx: {
  module: string;
  node?: string;
}): string {
  const moduleLabel = WORKFLOW_MODULE_LABEL[ctx.module] ?? ctx.module;
  if (ctx.node) {
    const nodeLabel = WORKFLOW_NODE_LABEL[ctx.node] ?? ctx.node;
    return `帮我查看${moduleLabel}中"${nodeLabel}"状态的项目`;
  }
  return `给我${moduleLabel}的工作流概览`;
}

// 场景专属快捷问题和允许的操作 actionCode
const SCENE_CONFIG: Record<string, {
  quickQuestions: string[];
  allowedActionCodes: string[];
}> = {
  PRODUCTION_PLAN: {
    quickQuestions: ['创建生产计划', '查询生产批次', '查看排产进度', '生产效率分析'],
    allowedActionCodes: [
      'PRODUCTION_PLAN_CREATE', 'PROCESSING_BATCH_LIST', 'PRODUCTION_PLAN_QUERY',
      'PRODUCTION_TRACKING', 'REPHRASE', 'SHOW_INTENTS',
    ],
  },
  WORK_REPORT: {
    quickQuestions: ['今日生产汇报', '查询生产批次', '工人出勤情况', '产量统计'],
    allowedActionCodes: [
      'WORK_REPORT_CREATE', 'WORK_REPORT_QUERY', 'PROCESSING_BATCH_LIST',
      'PERSONNEL_QUERY', 'REPHRASE', 'SHOW_INTENTS',
    ],
  },
  QUALITY_CHECK: {
    quickQuestions: ['查看质检任务', '质检数据分析', '不合格批次', '质检标准查询'],
    allowedActionCodes: [
      'QUALITY_CHECK_LIST', 'QUALITY_CHECK_CREATE', 'QUALITY_ANALYSIS',
      'PROCESSING_BATCH_LIST', 'REPHRASE', 'SHOW_INTENTS',
    ],
  },
  SHIPMENT: {
    quickQuestions: ['查询发货记录', '创建发货单', '物流跟踪', '发货统计'],
    allowedActionCodes: [
      'SHIPMENT_QUERY', 'SHIPMENT_CREATE', 'SHIPMENT_LIFECYCLE',
      'REPHRASE', 'SHOW_INTENTS',
    ],
  },
  MATERIAL: {
    quickQuestions: ['查询原料库存', '原料入库记录', '库存预警', '供应商查询'],
    allowedActionCodes: [
      'MATERIAL_BATCH_QUERY', 'MATERIAL_INBOUND_QUERY', 'INVENTORY_ANALYSIS',
      'SUPPLIER_QUERY', 'REPHRASE', 'SHOW_INTENTS',
    ],
  },
  PURCHASE: {
    quickQuestions: ['创建采购订单', '查询采购记录', '供应商报价', '采购统计'],
    allowedActionCodes: [
      'PURCHASE_ORDER_CREATE', 'PURCHASE_ORDER_QUERY', 'SUPPLIER_QUERY',
      'REPHRASE', 'SHOW_INTENTS',
    ],
  },
  EQUIPMENT: {
    quickQuestions: ['设备报修', '查询设备状态', '设备维护记录', '设备运行统计'],
    allowedActionCodes: [
      'EQUIPMENT_MAINTENANCE', 'EQUIPMENT_STATUS_QUERY', 'EQUIPMENT_DIAGNOSIS',
      'REPHRASE', 'SHOW_INTENTS',
    ],
  },
  ATTENDANCE: {
    quickQuestions: ['今日考勤情况', '员工出勤统计', '请假审批', '排班查询'],
    allowedActionCodes: [
      'ATTENDANCE_QUERY', 'PERSONNEL_QUERY', 'SCHEDULING_LIST',
      'REPHRASE', 'SHOW_INTENTS',
    ],
  },
};

/** 骨架三条的宽度 (标准 W1): 92% / 78% / 62% */
const SKELETON_BAR_WIDTHS = ['92%', '78%', '62%'] as const;
/** 闪烁周期 1500ms: 0.6 → 1 → 0.6 各 750ms */
const SKELETON_HALF_CYCLE_MS = 750;

/**
 * 等回答时的骨架屏 (标准 W1): 立即出现, 三条闪烁条, 开了「减少动态效果」时静止。
 * ⚠️ 上面一行文字由调用方给 —— 对仓管员「骨架」本身不是一个看得懂的信号, 文字不许去掉。
 */
function LoadingSkeleton({ barStyle }: { barStyle: StyleProp<ViewStyle> }) {
  const pulse = useRef(new Animated.Value(1)).current;
  useEffect(() => {
    let cancelled = false;
    let loop: Animated.CompositeAnimation | null = null;
    AccessibilityInfo.isReduceMotionEnabled()
      .then((reduceMotion) => {
        if (cancelled || reduceMotion) return;
        loop = Animated.loop(Animated.sequence([
          Animated.timing(pulse, { toValue: 0.6, duration: SKELETON_HALF_CYCLE_MS, useNativeDriver: Platform.OS !== 'web' }),
          Animated.timing(pulse, { toValue: 1, duration: SKELETON_HALF_CYCLE_MS, useNativeDriver: Platform.OS !== 'web' }),
        ]));
        loop.start();
      })
      .catch(() => { /* 读不到系统设置就保持静止, 骨架照样显示 */ });
    return () => {
      cancelled = true;
      loop?.stop();
    };
  }, [pulse]);
  return (
    <View testID="ai-chat-loading-skeleton" style={styles.loadingSkeletonGroup}>
      {SKELETON_BAR_WIDTHS.map((width) => (
        <Animated.View key={width} style={[styles.loadingSkeletonBar, barStyle, { width, opacity: pulse }]} />
      ))}
    </View>
  );
}

export default function AIChatScreen() {
  const { user } = useAuthStore();
  const getUserRole = useAuthStore((state) => state.getUserRole);
  const factoryId = user?.userType === 'factory' ? user.factoryUser.factoryId : '';
  const actorKey = `${factoryId}|${String(user?.id ?? '')}|${getUserRole() || 'factory_super_admin'}`;
  const actorRef = useRef({ key: actorKey, generation: 0 });
  if (actorRef.current.key !== actorKey) {
    actorRef.current = { key: actorKey, generation: actorRef.current.generation + 1 };
  }
  // Remount synchronously so old messages, typed text and voice previews never
  // render for a different authenticated actor while effect cleanup is pending.
  return <AIChatScreenContent key={`${actorKey}|${actorRef.current.generation}`} />;
}

function AIChatScreenContent() {
  const navigation = useNavigation();
  const route = useRoute<AIChatRouteProp>();
  const { user } = useAuthStore();
  const isRestaurantMode = isRestaurant(user);
  const { t } = useTranslation('home');
  const scrollViewRef = useRef<ScrollView>(null);
  const shouldAutoScrollRef = useRef(true);
  const theme = useTheme<AppTheme>();
  // 对话面单独一套字体栈 (标准 T1): 嵌套 Paper 主题只在 web 上换 fonts, 原生端原样返回; ⛔ 不改全局 src/theme
  const chatTheme = useMemo(() => withChatFonts(theme), [theme]);
  const { width: windowWidth } = useWindowDimensions();
  // 用户气泡最宽 min(内容列 78%, 320) (标准 L2)
  const userBubbleMaxWidth = Math.min((windowWidth - 32) * 0.78, 320);
  // 颜色层: 布局在文件底部的 StyleSheet, 颜色全部从这里取 token, 渲染时合并。
  // ⛔ 本文件不许出现 #RRGGBB / fontSize: 10..13 (闸: gates/demoScreensUseThemeTokens)。
  const tone = useMemo(() => ({
    screen: { backgroundColor: theme.colors.background },
    onSurface: { color: theme.colors.onSurface },
    muted: { color: theme.colors.onSurfaceVariant },
    onPrimaryContainer: { color: theme.colors.onPrimaryContainer },
    primaryText: { color: theme.colors.primary },
    errorText: { color: theme.colors.error },
    // 用户气泡用 primaryContainer/onPrimaryContainer (6.83:1); onPrimary on primary 只有 3.24:1, 正文过不了 AA
    userBubble: { backgroundColor: theme.colors.primaryContainer },
    suggestedAction: { backgroundColor: theme.colors.primaryContainer, borderColor: theme.colors.primary },
    inputArea: { backgroundColor: theme.colors.surface, borderTopColor: theme.colors.outlineVariant },
    inputWrapper: { backgroundColor: theme.colors.surfaceVariant, borderColor: theme.colors.outline },
    voicePreview: { backgroundColor: theme.colors.primaryContainer, borderBottomColor: theme.colors.outlineVariant },
    primaryButton: { backgroundColor: theme.colors.primary },
    primaryButtonIdle: { backgroundColor: theme.colors.primaryContainer },
    subtleButton: { backgroundColor: theme.colors.surfaceVariant },
    // 骨架条: outlineVariant(#F3F4F6) 落在页面背景 #F5F5F5 上看不见 (网页版实测), 用 textTertiary 再整体压淡
    loadingSkeleton: { backgroundColor: theme.colors.textTertiary },
    errorBanner: { backgroundColor: theme.colors.errorContainer, borderColor: theme.colors.error },
    errorBannerText: { color: theme.colors.onErrorContainer },
    retryButton: { backgroundColor: theme.colors.error },
    retryText: { color: theme.colors.onError },
  }), [theme]);

  // 从路由参数获取场景类型
  const params = route.params as Record<string, any> | undefined;
  const entityType = params?.entityType as string | undefined;
  const sceneConfig = entityType ? SCENE_CONFIG[entityType] : undefined;

  // 快捷问题列表：场景专属 > 通用
  const QUICK_QUESTIONS = sceneConfig?.quickQuestions || (isRestaurantMode ? [
    t('aiChat.restaurantQuestion1'),
    t('aiChat.restaurantQuestion2'),
    t('aiChat.restaurantQuestion3'),
    t('aiChat.restaurantQuestion4'),
  ] : [
    t('aiChat.question1'),
    t('aiChat.question2'),
    t('aiChat.question3'),
    t('aiChat.question4'),
  ]);

  const [messages, setMessages] = useState<Message[]>([]);
  const [inputText, setInputText] = useState('');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(false);
  const [showSlowLoading, setShowSlowLoading] = useState(false);

  // P1: Voice-first input mode
  const [showKeyboard, setShowKeyboard] = useState(false);
  // P1: Voice preview before auto-send
  const [previewText, setPreviewText] = useState('');
  const previewTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const previewGenerationRef = useRef(0);
  const isMountedRef = useRef(true);
  const sendPendingRef = useRef(false);
  // 写确认: 同一渲染里的连点靠这个同步锁挡住 (与 sendPendingRef 同一模式), 后端原子认领是最后一道
  const writeConfirmPendingRef = useRef(false);
  // 已发出的预览请求 `${messageId}:${previewAttempt}` —— 每次 (重新) 生成预览只请求一次
  const requestedPreviewKeysRef = useRef(new Set<string>());
  const sessionIdRef = useRef<string | null>(null);
  sessionIdRef.current = sessionId;
  // 最近一次成功执行的「上一步」(见 readLastAction); 换账号/换厂时清空。
  const lastActionRef = useRef<HeldLastAction | null>(null);

  // Get user role for card grid and audio feedback
  const getUserRole = useAuthStore((s) => s.getUserRole);
  const resolvedUserRole = getUserRole();
  const userRole = resolvedUserRole || 'factory_super_admin';
  const currentFactoryId = user?.userType === 'factory' ? user.factoryUser.factoryId : '';
  const identityKey = `${currentFactoryId}|${String(user?.id ?? '')}|${userRole}`;
  const sessionIdentityRef = useRef({ key: identityKey, generation: 0 });
  // Invalidate callbacks during render, before an effect can clear the old UI.
  if (sessionIdentityRef.current.key !== identityKey) {
    sessionIdentityRef.current = {
      key: identityKey,
      generation: sessionIdentityRef.current.generation + 1,
    };
  }
  const renderIdentityGeneration = sessionIdentityRef.current.generation;
  const isCurrentActor = () => {
    const live = useAuthStore.getState();
    const liveUser = live.user;
    const liveFactoryId = liveUser?.userType === 'factory' ? liveUser.factoryUser.factoryId : '';
    const liveKey = `${liveFactoryId}|${String(liveUser?.id ?? '')}|${live.getUserRole() || 'factory_super_admin'}`;
    return isMountedRef.current && liveKey === identityKey
      && sessionIdentityRef.current.key === identityKey
      && sessionIdentityRef.current.generation === renderIdentityGeneration;
  };
  useEffect(() => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    ++previewGenerationRef.current;
    if (previewTimer.current) clearTimeout(previewTimer.current);
    previewTimer.current = null;
    sendPendingRef.current = false;
    writeConfirmPendingRef.current = false;
    requestedPreviewKeysRef.current.clear();
    lastActionRef.current = null;
    setSessionId(null);
    setMessages([]);
    setInputText('');
    setPreviewText('');
    setStreamStatus(null);
    setIsLoading(false);
  }, [identityKey]);
  // P1: Voice result handler — show preview then auto-send
  const handleVoiceResult = (text: string) => {
    if (!isCurrentActor()) return;
    if (previewTimer.current) clearTimeout(previewTimer.current);
    const generation = ++previewGenerationRef.current;
    setPreviewText(text);
    previewTimer.current = setTimeout(() => {
      if (!isCurrentActor() || previewGenerationRef.current !== generation) return;
      previewTimer.current = null;
      handleSend(text);
      setPreviewText('');
    }, 1500);
  };

  // Cancel voice preview
  const cancelVoicePreview = useCallback(() => {
    ++previewGenerationRef.current;
    if (previewTimer.current) {
      clearTimeout(previewTimer.current);
      previewTimer.current = null;
    }
    setPreviewText('');
  }, []);

  // Invalidate queued preview callbacks as well as the active timer on unmount.
  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      ++previewGenerationRef.current;
      if (previewTimer.current) clearTimeout(previewTimer.current);
      previewTimer.current = null;
    };
  }, []);

  // P2: Handle card grid actions
  // 卡片落点可能在另一个 tab 的 stack 里: 溯源 / 拍照核验 / 个体档案在 BossOverviewTab 的
  // OverviewStack, 而本屏在 BossAnalysisTab 的 SmartBIStack 下。navigate(screen) 只在父 tab 的
  // routeNames 里找, 找不到就静默不动 —— 所以按父导航器真有的 tab 名投递到嵌套 stack。
  const handleCardNavigate = useCallback((screen: string, params?: Record<string, unknown>) => {
    try {
      const parent = navigation.getParent();
      const parentRouteNames: string[] = parent?.getState?.()?.routeNames ?? [];
      if (parent && parentRouteNames.includes('BossOverviewTab')) {
        parent.navigate('BossOverviewTab', { screen, params });
      } else if (parent) {
        parent.navigate('FAManagementTab', { screen, params });
      } else {
        navigation.dispatch(CommonActions.navigate(screen, params));
      }
    } catch {
      // Fallback: send as intent
      handleSend(screen);
    }
  }, [navigation]);

  // 实时检测当前输入的分析模式
  const detectedMode = useMemo(() => {
    if (!inputText.trim()) return null;
    return detectAnalysisMode(inputText);
  }, [inputText]);

  // 生成唯一消息ID
  const generateMessageId = () => `msg_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;

  // 滚动到底部
  const scrollToBottom = useCallback(() => {
    if (!shouldAutoScrollRef.current) return;
    setTimeout(() => {
      if (shouldAutoScrollRef.current) scrollViewRef.current?.scrollToEnd({ animated: true });
    }, 100);
  }, []);

  const handleMessageScroll = useCallback((event: { nativeEvent: { contentOffset: { y: number }; contentSize: { height: number }; layoutMeasurement: { height: number } } }) => {
    const { contentOffset, contentSize, layoutMeasurement } = event.nativeEvent;
    const distanceFromBottom = contentSize.height - (contentOffset.y + layoutMeasurement.height);
    shouldAutoScrollRef.current = distanceFromBottom <= AUTO_SCROLL_THRESHOLD;
  }, []);

  // 用于取消流式请求的 AbortController
  const abortControllerRef = useRef<AbortController | null>(null);

  const resetConversation = useCallback(() => {
    abortControllerRef.current?.abort();
    abortControllerRef.current = null;
    ++previewGenerationRef.current;
    if (previewTimer.current) clearTimeout(previewTimer.current);
    previewTimer.current = null;
    sendPendingRef.current = false;
    writeConfirmPendingRef.current = false;
    requestedPreviewKeysRef.current.clear();
    lastActionRef.current = null;
    sessionIdRef.current = null;
    setSessionId(null);
    setMessages([]);
    setInputText('');
    setPreviewText('');
    setStreamStatus(null);
    setIsLoading(false);
  }, []);

  const requestNewConversation = useCallback(() => {
    const hasPendingWrite = messages.some((message) => message.writeConfirm?.phase === 'ready');
    const hasExecutingWrite = messages.some((message) => message.writeConfirm?.phase === 'executing');
    if (hasExecutingWrite) {
      appAlert('操作正在执行', '请等待当前操作完成后再开始新对话。');
      return;
    }
    if (!hasPendingWrite) {
      resetConversation();
      return;
    }
    appAlert('确认开始新对话', '还有未确认的操作卡，丢弃后不会执行任何写入。', [
      { text: '继续保留', style: 'cancel' },
      { text: '丢弃并开始', style: 'destructive', onPress: resetConversation },
    ]);
  }, [messages, resetConversation]);

  useEffect(() => {
    navigation.setOptions?.({
      headerTintColor: theme.colors.onPrimary,
      headerRight: () => (
        <TouchableOpacity
          testID="ai-chat-new-conversation"
          accessibilityRole="button"
          accessibilityLabel="新对话"
          onPress={requestNewConversation}
          style={{ minWidth: 80, minHeight: 44, justifyContent: 'center', alignItems: 'center', marginRight: 8 }}
        >
          <Text variant="labelLarge" style={{ color: theme.colors.onPrimary }}>新对话</Text>
        </TouchableOpacity>
      ),
    });
    return () => navigation.setOptions?.({ headerRight: undefined });
  }, [navigation, requestNewConversation, theme.colors.primary]);

  // 清理函数
  useEffect(() => {
    return () => {
      abortControllerRef.current?.abort();
      abortControllerRef.current = null;
      sendPendingRef.current = false;
    };
  }, []);

  // 从其他页面跳转过来时自动发送初始消息
  const initialMessageSentRef = useRef(false);
  useEffect(() => {
    const params = route.params as Record<string, any> | undefined;
    // 显式 initialMessage / initialQuery 优先, 否则从 workflow entryContext 自动构造 (U-NAV-1)
    const explicitMsg = params?.initialMessage || params?.initialQuery;
    const entryContext = params?.entryContext;
    const initialMsg = explicitMsg
      || (entryContext?.module ? buildWorkflowGreeting(entryContext) : undefined);
    if (initialMsg && !initialMessageSentRef.current) {
      initialMessageSentRef.current = true;
      // 延迟发送，等待组件完全挂载
      setTimeout(() => {
        handleSend(initialMsg);
      }, 500);
    }
  }, [route.params]);

  // 流式状态文本（显示处理进度）
  const [streamStatus, setStreamStatus] = useState<string | null>(null);
  useEffect(() => {
    setShowSlowLoading(streamStatus === '正在处理');
  }, [streamStatus]);

  // 处理建议选项点击
  const handleSuggestedActionClick = async (action: SuggestedAction, messageId: string) => {
    // 处理跳转到生产计划创建表单的特殊action
    if (action.value === 'REDIRECT_TO_PLAN_FORM') {
      const extractedParams = action.params || action.data || {};
      navigation.dispatch(
        CommonActions.navigate('ProductionPlanManagement', {
          mode: 'create',
          initialValues: extractedParams,
        })
      );
      return;
    }

    // 澄清候选: 用「原话 + 选中的意图码」重新执行, 而不是把按钮名字当一句新话再识别一遍
    // (否则「就是六十箱」里的数量会丢)。同时把候选一起带上, 后端记成一条学习样本。
    const sourceMessage = messages.find((msg) => msg.id === messageId);
    const pickedIntent = action.value === 'SELECT_INTENT' ? action.params?.intentCode : undefined;
    const originalQuestion = sourceMessage?.userQuestion;

    // 清除该消息的建议选项（已选择）
    setMessages((prev) =>
      prev.map((msg) =>
        msg.id === messageId ? { ...msg, suggestedActions: undefined } : msg
      )
    );
    if (typeof pickedIntent === 'string' && pickedIntent && originalQuestion) {
      const candidates = (sourceMessage?.suggestedActions || [])
        .map((a) => (a.value === 'SELECT_INTENT' ? a.params?.intentCode : undefined))
        .filter((c): c is string => typeof c === 'string' && c.length > 0);
      // 按钮上除意图码以外的参数 (追问确认按钮带的 taskId / 数量 / followUpRule 等) 一并带回去;
      // 澄清字段放在后面, 按钮参数盖不掉它们。
      const buttonParams = Object.fromEntries(
        Object.entries(action.params || {}).filter(([key]) => key !== 'intentCode'),
      );
      await handleSend(originalQuestion, {
        displayText: action.label,
        intentCode: pickedIntent,
        context: { ...buttonParams, clarificationSelection: true, clarificationCandidates: candidates },
      });
      return;
    }
    // 发送用户选择作为新消息
    await handleSend(action.label);
  };

  // 发送消息 — 使用真正的 SSE 流式传输
  const handleSend = async (
    text?: string,
    sendOptions?: { displayText?: string; intentCode?: string; context?: Record<string, unknown> },
  ) => {
    const messageText = text || inputText.trim();
    if (!isCurrentActor() || !currentFactoryId || !messageText || isLoading || sendPendingRef.current) return;

    const requestIdentityGeneration = renderIdentityGeneration;
    const controller = new AbortController();
    abortControllerRef.current = controller;
    const isCurrentStream = () => isCurrentActor()
      && sessionIdentityRef.current.generation === requestIdentityGeneration
      && !controller.signal.aborted;

    const startTime = Date.now();
    const assistantMessageId = generateMessageId();
    let streamedContent = '';
    let resultData: Record<string, unknown> | null = null;
    let slowTimer: ReturnType<typeof setTimeout> | null = null;
    let timeoutTimer: ReturnType<typeof setTimeout> | null = null;
    const clearStreamTimers = () => {
      if (slowTimer) clearTimeout(slowTimer);
      if (timeoutTimer) clearTimeout(timeoutTimer);
      slowTimer = null;
      timeoutTimer = null;
    };
    controller.signal.addEventListener('abort', clearStreamTimers, { once: true });

    // React state updates do not synchronously guard another press in this render.
    sendPendingRef.current = true;
    try {
      setInputText('');
      setIsLoading(true);
      setStreamStatus(null);
      slowTimer = setTimeout(() => {
        if (isCurrentStream()) setStreamStatus('正在处理');
      }, 3000);
      timeoutTimer = setTimeout(() => {
        if (isCurrentStream()) {
          setMessages((prev) => prev.map((msg) => msg.id === assistantMessageId
            ? { ...msg, content: '这次没处理完，请再说一遍。没有执行任何写操作，写操作都要先经过确认卡。', isLoading: false, error: true }
            : msg));
          setStreamStatus(null);
          setIsLoading(false);
          sendPendingRef.current = false;
          controller.abort();
        }
      }, AI_CHAT_TIMEOUT_MS);
      const modeResult = detectAnalysisMode(messageText);

      const userMessage: Message = {
        id: generateMessageId(),
        role: 'user',
        content: sendOptions?.displayText || messageText,
        timestamp: new Date(),
      };
      setMessages((prev) => [...prev, userMessage]);
      scrollToBottom();
      setMessages((prev) => [
        ...prev,
        {
          id: assistantMessageId,
          role: 'assistant',
          content: '',
          timestamp: new Date(),
          isLoading: true,
          userQuestion: messageText,
        },
      ]);
      scrollToBottom();

      const factoryId = currentFactoryId;
      if (!factoryId) {
        throw new Error('当前账号缺少工厂标识，请重新登录');
      }
      const callbacks: IntentSSECallbacks = {
        isCurrentRequest: isCurrentStream,
        onSessionId: (serverSessionId) => {
          if (!isCurrentStream()) return;
          setSessionId(serverSessionId);
        },
        onStart: (message) => {
          if (!isCurrentStream()) return;
          setStreamStatus(message);
          console.log('[AI Stream] Start:', message);
        },

        onCacheHit: (data) => {
          if (!isCurrentStream()) return;
          setStreamStatus('命中缓存，快速响应...');
          console.log('[AI Stream] Cache hit:', data.cacheType, data.latencyMs + 'ms');
        },

        onCacheMiss: (latencyMs) => {
          if (!isCurrentStream()) return;
          console.log('[AI Stream] Cache miss:', latencyMs + 'ms');
        },

        onProgress: (data) => {
          if (!isCurrentStream()) return;
          setStreamStatus(data.message || data.stage);
          console.log('[AI Stream] Progress:', data.stage, data.message);
        },

        onIntentRecognized: (data) => {
          if (!isCurrentStream()) return;
          setStreamStatus(`识别到: ${data.intentName}`);
          console.log('[AI Stream] Intent:', data.intentCode, data.confidence);
          // 更新消息的意图信息
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantMessageId
                ? { ...msg, intentCode: data.intentCode }
                : msg
            )
          );
        },

        onExecuting: (intentName) => {
          if (!isCurrentStream()) return;
          setStreamStatus(`正在执行: ${intentName}...`);
          console.log('[AI Stream] Executing:', intentName);
        },

        onMeta: (data) => {
          if (!isCurrentStream()) return;
          console.log('[AI Stream] Meta:', data.model, data.questionType);
          // 更新分析模式
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantMessageId
                ? {
                    ...msg,
                    mode: data.questionType === 'GENERAL_QUESTION' ? 'deep' as AnalysisMode : 'quick' as AnalysisMode,
                  }
                : msg
            )
          );
        },

        onToken: (token) => {
          if (!isCurrentStream()) return;
          // 逐 token 实时显示 — 这是真正的流式！
          streamedContent += token;
          setStreamStatus(null); // 收到内容后隐藏状态文本
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantMessageId
                ? { ...msg, content: streamedContent, isLoading: true }
                : msg
            )
          );
          scrollToBottom();
        },

        onResult: (result) => {
          if (!isCurrentStream()) return;
          resultData = result;
          console.log('[AI Stream] Result status:', result.status);
          const nextLastAction = readLastAction(result.metadata);
          if (nextLastAction) {
            lastActionRef.current = { value: nextLastAction, receivedAt: Date.now() };
          }

          // 从结果中提取回复文本
          let replyMessage = '';
          const formattedText = result.formattedText as string | undefined;
          const message = result.message as string | undefined;
          const resultInner = result.resultData as Record<string, unknown> | undefined;

          if (formattedText) {
            replyMessage = formattedText;
          } else if (message && message !== '执行成功') {
            replyMessage = message;
          }

          // 追加分析摘要
          if (resultInner) {
            const analysis = (resultInner.analysis || resultInner.summary) as string | undefined;
            if (analysis) {
              replyMessage = replyMessage ? `${replyMessage}\n\n${analysis}` : analysis;
            }
          }

          // 写操作被 W0 写闸拦下 (WRITE_CONFIRM_REQUIRED) 或直接回了可确认预览: 出确认卡。
          // 气泡正文用确认卡给的话 —— PREVIEW 的 message 是工具预览的原始 JSON, 不能直接给人看。
          const writeConfirm = writeConfirmFromStreamResult(result, messageText);
          if (writeConfirm) {
            replyMessage = writeConfirm.leadText;
          }

          // 如果有结果文本且没有流式内容，显示结果
          if (replyMessage && !streamedContent) {
            streamedContent = replyMessage;
          }

          // 提取建议操作
          let suggestedActions: SuggestedAction[] = [];
          let followUps: FollowUpSuggestion[] = [];
          const status = result.status as string | undefined;
          const rawFollowUps = Array.isArray(result.followUps)
            ? result.followUps
            : Array.isArray(result.nextQuestions) ? result.nextQuestions : [];
          followUps = mapFollowUps(rawFollowUps);

          // NEED_MORE_INFO: 优先使用 clarificationQuestions 构建友好回复 (正文在下面选项定下来之后再拼:
          // 「或点击下方选项」只在真的有选项时才说 —— prod 2026-10-06 没有选项也这么说了)
          const needMoreInfoQuestions = status === 'NEED_MORE_INFO' && Array.isArray(result.clarificationQuestions)
            ? (result.clarificationQuestions as unknown[]).filter((q): q is string => typeof q === 'string' && q.trim() !== '')
            : [];

          // 重定向到表单
          if (result.actionCode === 'REDIRECT_TO_PLAN_FORM' || result.redirectAction === 'REDIRECT_TO_PLAN_FORM') {
            const params = (result.collectedParams || result.params || {}) as Record<string, unknown>;
            suggestedActions = [{
              label: '前往创建生产计划',
              value: 'REDIRECT_TO_PLAN_FORM',
              description: '使用AI收集的参数预填表单',
              params,
            }];
          }

          // 澄清建议 / 缺参可选项
          if ((status === 'NEED_CLARIFICATION' || status === 'CONVERSATION_CONTINUE' || status === 'ACTIVE' || status === 'NEED_MORE_INFO')
              && Array.isArray(result.suggestedActions)) {
            suggestedActions = (result.suggestedActions as Array<Record<string, unknown>>).map((action) => ({
              label: (action.actionName || action.label || action.name || action.description) as string,
              value: (action.actionCode || action.value || action.code || action.actionName) as string,
              description: action.description as string | undefined,
              params: (action.parameters || action.params) as Record<string, unknown> | undefined,
            })).filter((a) => a.label && a.value);
          }

          // 候选意图
          if (suggestedActions.length === 0 && Array.isArray(result.candidates) && (result.candidates as unknown[]).length > 0) {
            suggestedActions = (result.candidates as Array<Record<string, unknown>>).map((c) => ({
              label: (c.intentName || c.intentCode) as string,
              value: c.intentCode as string,
              description: c.matchReason as string | undefined,
            })).filter((a) => a.label && a.value);
          }

          // 提取食品知识库元数据
          let foodKbMeta: FoodKBQueryMetadata | undefined;
          if (resultInner?.citations && Array.isArray(resultInner.citations)) {
            foodKbMeta = {
              responseTimeMs: (resultInner.latencyMs as number) || (Date.now() - startTime),
              documentCount: resultInner.documentCount as number,
              citations: (resultInner.citations as Array<Record<string, unknown>>).map(c => ({
                index: (c.index as number) || 0,
                title: (c.title as string) || '',
                source: (c.source as string) || '',
                category: (c.category as string) || '',
                similarity: (c.similarity as number) || 0,
              })),
            };
          }

          // 检测结构化数据用于富组件渲染
          const richData = detectRichData(resultInner);
          const metadata = result.metadata;
          const agentRun = metadata && typeof metadata === 'object' && !Array.isArray(metadata)
            ? parseRestaurantAgentLaunch(
                (metadata as Record<string, unknown>).agentRun,
                factoryId,
              )
            : undefined;

          // 场景过滤：只保留当前场景允许的建议操作
          if (sceneConfig && suggestedActions.length > 0) {
            suggestedActions = suggestedActions.filter(
              (a) => a.value === 'SELECT_INTENT' || sceneConfig.allowedActionCodes.includes(a.value)
            );
          }

          if (needMoreInfoQuestions.length > 0) {
            const tail = suggestedActions.length > 0 ? '请直接告诉我，或点击下方选项。' : '请直接告诉我。';
            replyMessage = `为了完成操作，还需要以下信息：\n\n${needMoreInfoQuestions.map((q, i) => `${i + 1}. ${q}`).join('\n')}\n\n${tail}`;
            streamedContent = replyMessage;
          }

          // 更新消息
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantMessageId
                ? {
                    ...msg,
                    content: streamedContent || replyMessage || t('aiChat.defaultReply'),
                    intentCode: (result.intentCode as string) || msg.intentCode,
                    intentCategory: result.intentCategory as string | undefined,
                    resultStatus: status,
                    suggestedActions: suggestedActions.length > 0 ? suggestedActions : undefined,
                    followUps: followUps.length > 0 ? followUps : undefined,
                    foodKbMetadata: foodKbMeta,
                    richData,
                    agentRun,
                    agentRunFactoryId: agentRun ? factoryId : undefined,
                    agentRunOwnerUserId: agentRun ? String(user?.id ?? '') : undefined,
                    writeConfirm,
                  }
                : msg
            )
          );
        },

        onComplete: (data) => {
          if (!isCurrentStream()) return;
          console.log('[AI Stream] Complete:', data.status, data.totalLatencyMs + 'ms');

          // 如果 complete 带有 fullContent 且流式没收到内容，用 fullContent
          if (data.fullContent && !streamedContent) {
            streamedContent = data.fullContent;
          }

          // 最终更新消息状态
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantMessageId
                ? {
                    ...msg,
                    content: streamedContent || msg.content || t('aiChat.defaultReply'),
                    isLoading: false,
                    mode: msg.mode || modeResult.mode,
                    responseTimeMs: data.totalLatencyMs || (Date.now() - startTime),
                  }
                : msg
            )
          );
          setStreamStatus(null);
          setIsLoading(false);
          scrollToBottom();

          // P5: Audio feedback for WRITE operations
          if (resultData) {
            const category = (resultData as Record<string, unknown>).intentCategory as string | undefined;
            const status = (resultData as Record<string, unknown>).status as string | undefined;
            const isWrite = category === 'DATA_OPERATION' || category === 'WRITE';
            if (isWrite && status === 'SUCCESS') {
              const msg = (resultData as Record<string, unknown>).message as string || '操作成功';
              feedbackSounds.onWriteSuccess(msg, userRole);
            } else if (status === 'NEED_MORE_INFO') {
              feedbackSounds.onNeedMoreInfo(userRole);
            }
          }
        },

        onError: (message) => {
          if (!isCurrentStream()) return;
          console.error('[AI Stream] Error:', message);
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantMessageId
                ? {
                    ...msg,
                    content: (() => {
                      const raw = message || '';
                      if (/timeout|aborted|abort|超时/i.test(raw)) return '这次没处理完，请再说一遍。没有执行任何写操作，写操作都要先经过确认卡。';
                      if (/factory|工厂标识/i.test(raw)) return '当前账号没有关联工厂，请重新登录后再试。';
                      return '这次没有处理成功，请检查网络后再试。没有执行任何写操作。';
                    })(),
                    isLoading: false,
                    error: true,
                  }
                : msg
            )
          );
          setStreamStatus(null);
          setIsLoading(false);
          // P5: Error audio feedback
          feedbackSounds.onError(userRole);
        },
      };

      // 最近对话只辅助识别；分页来源继续由服务端归属会话决定。
      const recentTurns = buildRecentTurns(messages);
      // 上一步只在 10 分钟内有效; 过期就不带, 服务端也就不会拿它解读这一句。
      const lastAction = pickFreshLastAction(lastActionRef.current, Date.now());
      if (sendOptions?.intentCode || sendOptions?.context || recentTurns.length > 0 || lastAction || sessionId) {
        await aiApiClient.executeIntentStream(messageText, callbacks, factoryId, entityType, {
          ...(sendOptions?.intentCode ? { intentCode: sendOptions.intentCode } : {}),
          ...((sendOptions?.context || recentTurns.length > 0 || lastAction) ? {
            context: {
              ...(sendOptions?.context || {}),
              ...(recentTurns.length > 0 ? { recentTurns } : {}),
              ...(lastAction ? { lastAction } : {}),
            },
          } : {}),
          ...(sessionId ? { sessionId } : {}),
        }, controller.signal);
      } else {
        await aiApiClient.executeIntentStream(messageText, callbacks, factoryId, entityType, undefined, controller.signal);
      }

    } catch (error) {
      if (!isCurrentStream()) return;
      console.error('AI 对话失败:', error);

      // 如果流式已经有内容，保留已有内容
      if (streamedContent) {
        setMessages((prev) =>
          prev.map((msg) =>
            msg.id === assistantMessageId
              ? { ...msg, isLoading: false, responseTimeMs: Date.now() - startTime }
              : msg
          )
        );
      } else {
        // 无内容时显示错误
        const rawError = error instanceof Error ? error.message : '';
        const errorMessage = /timeout|aborted|abort|超时/i.test(rawError)
          ? '这次没处理完，请再说一遍。没有执行任何写操作，写操作都要先经过确认卡。'
          : (/factory|工厂标识/i.test(rawError)
            ? '当前账号没有关联工厂，请重新登录后再试。'
            : '这次没有处理成功，请检查网络后再试。没有执行任何写操作。');
        setMessages((prev) =>
          prev.map((msg) =>
            msg.id === assistantMessageId
              ? { ...msg, content: errorMessage, isLoading: false, error: true }
              : msg
          )
        );
      }
      setStreamStatus(null);
      setIsLoading(false);
    } finally {
      if (abortControllerRef.current === controller) abortControllerRef.current = null;
      controller.signal.removeEventListener('abort', clearStreamTimers);
      clearStreamTimers();
      if (isCurrentStream()) {
        sendPendingRef.current = false;
        setIsLoading(false);
      }
    }
  };

  // ==================== 写操作确认 (预览 → 确认执行 / 取消) ====================

  /** 只改 messageId 那张卡; guard 不过 (旧回包 / 状态已变) 就原样返回。 */
  const updateWriteConfirm = (
    messageId: string,
    guard: (state: WriteConfirmState) => boolean,
    next: (state: WriteConfirmState) => WriteConfirmState,
  ) => {
    setMessages((prev) => prev.map((msg) => (
      msg.id === messageId && msg.writeConfirm && guard(msg.writeConfirm)
        ? { ...msg, writeConfirm: next(msg.writeConfirm) }
        : msg
    )));
  };

  const appendAssistantMessage = (extra: Partial<Message> & { content: string }) => {
    setMessages((prev) => [
      ...prev,
      { id: generateMessageId(), role: 'assistant', timestamp: new Date(), ...extra },
    ]);
    scrollToBottom();
  };

  /** 用「原话 + 被拦的 intentCode + sessionId」请求预览 (POST /preview, 服务端强制 previewOnly)。 */
  const requestWritePreview = async (messageId: string, state: WriteConfirmState) => {
    const attempt = state.previewAttempt;
    const stillWaiting = (s: WriteConfirmState) => s.phase === 'preparing' && s.previewAttempt === attempt;
    const factoryId = currentFactoryId;
    try {
      const response = await intentConfigApiClient.previewIntent({
        userInput: state.userInput,
        ...(state.intentCode ? { intentCode: state.intentCode } : {}),
        ...(sessionIdRef.current ? { sessionId: sessionIdRef.current } : {}),
        ...(entityType ? { entityType } : {}),
        // 流式那一轮已经定下的参数 (任务号 / 产量 …), 预览不必再从原话重新抽
        ...(state.previewContext ? { context: state.previewContext } : {}),
      }, factoryId);
      if (!isCurrentActor()) return;
      // 预览追问 (比如「要领用哪个批次？」) 服务端开了参数收集会话: 记下它的 id, 用户下一句的回答
      // 带着它发出去, 服务端就接着填槽继续同一个意图 (2026-10-07 真机: 答案被当新话识别成溯源查询)。
      const askedSessionId = response?.status === 'NEED_MORE_INFO' && typeof response.sessionId === 'string'
        ? response.sessionId.trim() : '';
      if (askedSessionId) {
        sessionIdRef.current = askedSessionId;
        setSessionId(askedSessionId);
      }
      updateWriteConfirm(messageId, stillWaiting, (s) => applyPreviewResponse(s, response, Date.now()));
    } catch (error) {
      if (!isCurrentActor()) return;
      updateWriteConfirm(messageId, stillWaiting, (s) => previewRequestFailed(s, error));
    }
  };

  // 卡片进入 preparing (首次 / 重新生成) 时发一次预览请求
  useEffect(() => {
    if (!currentFactoryId) return;
    for (const msg of messages) {
      const state = msg.writeConfirm;
      if (!state || state.phase !== 'preparing') continue;
      const key = `${msg.id}:${state.previewAttempt}`;
      if (requestedPreviewKeysRef.current.has(key)) continue;
      requestedPreviewKeysRef.current.add(key);
      void requestWritePreview(msg.id, state);
    }
  }, [messages]);

  const handleWriteConfirm = async (messageId: string) => {
    if (!isCurrentActor() || writeConfirmPendingRef.current) return;
    const state = messages.find((msg) => msg.id === messageId)?.writeConfirm;
    if (!state || state.phase !== 'ready' || !state.action) return;
    if (isPreviewExpired(state, Date.now())) {
      updateWriteConfirm(messageId, (s) => s.phase === 'ready', (s) => ({
        ...s, phase: 'failed', failureKind: 'expired', notice: EXPIRED_NOTICE, canRegenerate: true,
      }));
      return;
    }
    // 同步加锁: 同一渲染里第二次点击看不到 phase=executing, 只能靠 ref 挡住
    writeConfirmPendingRef.current = true;
    const action = state.action;
    const intentName = state.intentName;
    updateWriteConfirm(messageId, (s) => s.phase === 'ready', (s) => ({ ...s, phase: 'executing', notice: undefined }));
    let outcome: ConfirmOutcome;
    let resultInner: Record<string, unknown> | undefined;
    let resultMeta: { intentCode?: string; intentCategory?: string } = {};
    let confirmedLastAction: Record<string, unknown> | undefined;
    let confirmedFollowUps: FollowUpSuggestion[] = [];
    try {
      const response = await intentConfigApiClient.confirmIntent(buildConfirmationProof(action), currentFactoryId);
      outcome = classifyConfirmResponse(response, intentName);
      confirmedLastAction = readLastAction(response?.metadata);
      confirmedFollowUps = mapFollowUps(response?.followUps);
      const inner = response?.resultData;
      resultInner = inner && typeof inner === 'object' && !Array.isArray(inner)
        ? (inner as Record<string, unknown>)
        : undefined;
      resultMeta = {
        intentCode: response?.intentCode || state.intentCode,
        intentCategory: response?.intentCategory ?? undefined,
      };
    } catch (error) {
      outcome = classifyConfirmError(error, intentName);
    } finally {
      writeConfirmPendingRef.current = false;
    }
    if (!isCurrentActor()) return;

    if (outcome.kind === 'done') {
      // 确认卡做成的那一步与流式成功一样记成「上一步」: 「开始1827」确认之后说「做完了，产量0」,
      // 服务端才能把它解读成「完成 1827」的确认按钮 (FollowUpResolver)。只认真的执行了 —— 演示环境 /
      // 提交审批 (executed=false) 什么都没做, 不能当上一步。
      if (outcome.executed && confirmedLastAction) {
        lastActionRef.current = { value: confirmedLastAction, receivedAt: Date.now() };
      }
      const doneLabel = outcome.doneLabel;
      updateWriteConfirm(messageId, (s) => s.phase === 'executing', (s) => ({ ...s, phase: 'done', doneLabel }));
      appendAssistantMessage({
        content: outcome.content,
        followUps: outcome.executed && confirmedFollowUps.length > 0 ? confirmedFollowUps : undefined,
        richData: detectRichData(resultInner),
        intentCode: resultMeta.intentCode,
        intentCategory: resultMeta.intentCategory,
        userQuestion: state.userInput,
        sessionIdForReply: sessionIdRef.current || undefined,
      });
      if (outcome.executed) feedbackSounds.onWriteSuccess(outcome.content, userRole);
      return;
    }
    const { failureKind, notice, canRegenerate } = outcome;
    updateWriteConfirm(messageId, (s) => s.phase === 'executing', (s) => ({
      ...s, phase: 'failed', failureKind, notice, canRegenerate,
    }));
    feedbackSounds.onError(userRole);
  };

  const handleWriteCancel = (messageId: string) => {
    if (!isCurrentActor() || writeConfirmPendingRef.current) return;
    const state = messages.find((msg) => msg.id === messageId)?.writeConfirm;
    if (!state || state.phase !== 'ready') return;
    updateWriteConfirm(messageId, (s) => s.phase === 'ready', (s) => ({ ...s, phase: 'cancelled' }));
    appendAssistantMessage({ content: CANCELLED_REPLY });
  };

  const handleWriteRegenerate = (messageId: string) => {
    if (!isCurrentActor() || writeConfirmPendingRef.current) return;
    updateWriteConfirm(
      messageId,
      (s) => s.phase === 'blocked' || s.phase === 'failed' || s.phase === 'ready',
      (s) => ({
        ...s,
        phase: 'preparing',
        previewAttempt: s.previewAttempt + 1,
        action: undefined,
        receivedAtMs: undefined,
        notice: undefined,
        failureKind: undefined,
        canRegenerate: undefined,
      }),
    );
  };

  // 渲染消息
  const handleCopyMessage = useCallback((message: Message) => {
    if (!message.content || message.isLoading) return;
    appAlert('复制消息', '要把这条消息复制到剪贴板吗？', [
      { text: '取消', style: 'cancel' },
      { text: '复制', onPress: () => {
        void import('expo-clipboard')
          .then(({ setStringAsync }) => setStringAsync(message.content))
          .catch(() => appAlert('复制失败', '暂时无法复制，请稍后重试。'));
      } },
    ]);
  }, []);

  const renderMessage = (message: Message) => {
    const isUser = message.role === 'user';

    // 等回答: 立即给骨架屏 + 一行文字 (标准 W1)。文字 3 秒后由「正在理解你的话…」换成「正在处理」。
    if (message.isLoading && !message.content) {
      return (
        <View key={message.id} style={styles.assistantMessageRow}>
          <View testID="ai-chat-assistant-body" style={styles.aiMessageContainer}>
            <Text variant="bodyLarge" style={[styles.loadingText, tone.muted]}>
              {showSlowLoading ? '正在处理' : '正在理解你的话…'}
            </Text>
            <LoadingSkeleton barStyle={tone.loadingSkeleton} />
          </View>
        </View>
      );
    }

    if (isUser) {
      return (
        <Pressable key={message.id} style={styles.userMessageRow} onLongPress={() => handleCopyMessage(message)} delayLongPress={450}>
          <View testID="ai-chat-user-bubble" style={[styles.userMessageBubble, tone.userBubble, { maxWidth: userBubbleMaxWidth }]}>
            <Text variant="bodyLarge" style={[tone.onPrimaryContainer, styles.answerText]}>{message.content}</Text>
          </View>
        </Pressable>
      );
    }

    // 「完成的回答」才挂 👍/👎 (标准 F4): 不在加载、没出错、不是确认卡、没有澄清选项、状态不是在等用户下一步
    const isCompletedAnswer = !message.isLoading
      && !message.error
      && !message.writeConfirm
      && !(message.suggestedActions?.length)
      && !isPendingStatus(message.resultStatus)
      && Boolean(message.intentCode)
      && Boolean(message.userQuestion);

    return (
      <Pressable key={message.id} style={styles.assistantMessageRow} onLongPress={() => handleCopyMessage(message)} delayLongPress={450}>
        <View testID="ai-chat-assistant-body" style={styles.aiMessageContainer}>
          {/* 分析模式 + 耗时是工程遥测, 只在显式开关 EXPO_PUBLIC_AI_DEBUG_TELEMETRY=1 下给开发者看
              (⛔ 不用 __DEV__: 网页版是开发服务器, 恒为 true), 不露给业务用户 */}
          {AI_DEBUG_TELEMETRY && message.mode && !message.isLoading && (
            <View testID="ai-telemetry-row" style={styles.modeIndicatorRow}>
              <AIModeIndicator mode={message.mode} size="small" />
              {message.responseTimeMs ? (
                <Text variant="bodySmall" style={tone.muted}>
                  {(message.responseTimeMs / 1000).toFixed(1)}s
                </Text>
              ) : null}
            </View>
          )}
          {/* 助手回答直接排在页面背景上: 无气泡 / 无头像 / 无时间戳 (标准 L1 / L4) */}
          {/* 2026-07-24: resolver 答案已 markdown 化 (加粗/列表/引用块) —
              纯 Text 会露出裸 ** 星号, 完成态走 MarkdownRenderer;
              流式中仍用 Text (逐字追加 + 光标, markdown 半截标记无意义)。
              ⚠️ 两条路都保留 `\n`: 后端 Tool 让「每头牛一行」, 这里不许把换行吞掉。 */}
          {message.isLoading ? (
            <Text variant="bodyLarge" style={[tone.onSurface, styles.answerText]}>
              {message.content}
              {message.content ? (
                <Text variant="bodyLarge" style={[styles.streamingCursor, tone.primaryText]}>|</Text>
              ) : null}
            </Text>
          ) : message.error ? null : (
            <MarkdownRenderer content={message.content} bodyStyle={[styles.answerText, tone.onSurface, chatFontFamily]} />
          )}
          {/* 结构化数据富组件渲染（表格/卡片/统计） */}
          {!message.isLoading && message.richData && (
            <RichContentRenderer data={message.richData} />
          )}
          {message.error && !message.isLoading ? (
            <View testID="ai-chat-error-banner" style={[styles.errorBanner, tone.errorBanner]}>
              <Text variant="bodyMedium" style={[tone.errorBannerText, styles.answerText]}>{message.content}</Text>
              {message.userQuestion ? (
                <TouchableOpacity
                  testID="ai-chat-retry"
                  accessibilityRole="button"
                  accessibilityLabel="再试一次"
                  style={[styles.retryButton, tone.retryButton]}
                  onPress={() => { void handleSend(message.userQuestion); }}
                  disabled={isLoading}
                >
                  <Text variant="labelLarge" style={tone.retryText}>再试一次</Text>
                </TouchableOpacity>
              ) : null}
            </View>
          ) : null}
          {!message.isLoading && message.writeConfirm ? (
            <AIWriteConfirmCard
              state={message.writeConfirm}
              onConfirm={() => { void handleWriteConfirm(message.id); }}
              onCancel={() => handleWriteCancel(message.id)}
              onRegenerate={() => handleWriteRegenerate(message.id)}
            />
          ) : null}
          {!message.isLoading
            && message.agentRun
            && message.agentRunFactoryId === currentFactoryId
            && message.agentRunOwnerUserId === String(user?.id ?? '') ? (
            <RestaurantGrossMarginRunCard
              key={`restaurant-agent:${message.id}:${String(user?.id ?? '')}:${currentFactoryId}`}
              factoryId={currentFactoryId}
              ownerUserId={String(user?.id ?? '')}
              startDate={message.agentRun.startDate}
              endDate={message.agentRun.endDate}
              autoStart={message.agentRun.autoStart}
            />
          ) : null}
          {/* 显示建议操作按钮 (当需要澄清时): 竖排、占满正文列、一个样式 (标准 F3) */}
          {!message.isLoading && message.suggestedActions && message.suggestedActions.length > 0 && (
            <View style={styles.suggestedActionsContainer}>
              {message.suggestedActions.map((action, index) => (
                <TouchableOpacity
                  key={index}
                  testID={`suggested-action-${index}`}
                  accessibilityRole="button"
                  accessibilityLabel={action.label}
                  accessibilityState={{ disabled: isLoading }}
                  style={[styles.suggestedActionButton, tone.suggestedAction]}
                  onPress={() => handleSuggestedActionClick(action, message.id)}
                  disabled={isLoading}
                >
                  <Text variant="bodyLarge" style={[styles.leftAlignedText, tone.onPrimaryContainer]}>
                    {action.label}
                  </Text>
                  {/* 只有当 description 与 label 不同时才显示 */}
                  {action.description && action.description !== action.label && (
                    <Text variant="bodySmall" numberOfLines={1} style={[styles.leftAlignedText, styles.suggestedActionDesc, tone.muted]}>
                      {action.description}
                    </Text>
                  )}
                </TouchableOpacity>
              ))}
            </View>
          )}
          {/* 追问芯片 (后端明确给了 followUps 才渲染): 横排换行, 至少 40px 高 (标准 S3 / F2) */}
          {!message.isLoading && message.followUps && message.followUps.length > 0 && (
            <View testID="ai-chat-followups" style={styles.followUpContainer}>
              {message.followUps.map((followUp, index) => (
                <TouchableOpacity
                  key={`${followUp.question}-${index}`}
                  testID={`ai-chat-followup-${index}`}
                  accessibilityRole="button"
                  accessibilityLabel={followUp.label}
                  style={[styles.followUpChip, tone.suggestedAction]}
                  onPress={() => { void handleSend(followUp.question, {
                    displayText: followUp.label,
                    ...(followUp.intentCode ? { intentCode: followUp.intentCode } : {}),
                  }); }}
                  disabled={isLoading}
                >
                  <Text variant="bodyLarge" numberOfLines={1} ellipsizeMode="tail" style={[styles.centeredText, tone.onPrimaryContainer]}>
                    {followUp.label}
                  </Text>
                </TouchableOpacity>
              ))}
            </View>
          )}
          {/* 仅「完成的回答」显示统一答案质量反馈 (标准 F4); 澄清 / 追问 / 确认卡 / 错误都不出 */}
          {isCompletedAnswer && message.userQuestion && message.intentCode ? (
            <View testID="ai-chat-feedback">
              <FeedbackWidget
                queryId={message.id}
                question={message.userQuestion}
                answer={message.content}
                sessionId={message.sessionIdForReply || sessionId || undefined}
                intentCode={message.intentCode}
                foodKbMetadata={message.foodKbMetadata}
              />
            </View>
          ) : null}
        </View>
      </Pressable>
    );
  };

  // 与 QuickActionCardGrid 用同一个选择函数判断「这个角色有没有卡组」, 不另写一份角色清单
  const hasWelcomeCards = resolveCardFactory({
    userRole,
    isRestaurantMode,
    factoryId: currentFactoryId,
  }) !== undefined;

  // 渲染欢迎消息 — P2: 使用大卡片网格替代小文字快捷问题
  const renderWelcome = () => (
    <View style={styles.welcomeContainer}>
      {/* AI 欢迎消息 */}
      <View style={styles.assistantMessageRow}>
        <View testID="ai-chat-assistant-body" style={styles.aiMessageContainer}>
          <Text variant="bodyLarge" style={tone.onSurface}>
            {t(isRestaurantMode ? 'aiChat.restaurantWelcome' : 'aiChat.welcome')}
          </Text>
          {/* 没有卡组的角色(一线角色 / 调度 / HR …)下面什么都没有, 「我可以帮您：」就成了悬空冒号 ——
              改成告诉他下一步怎么做 (spec rn-frontline-ai-entry F3)。有卡组的角色不变。 */}
          <Text
            testID="ai-chat-welcome-next-step"
            variant="bodyLarge"
            style={[tone.onSurface, { marginTop: theme.custom.spacing.s }]}
          >
            {t(isRestaurantMode
              ? 'aiChat.restaurantCanHelp'
              : hasWelcomeCards ? 'aiChat.canHelp' : 'aiChat.voiceHint')}
          </Text>
        </View>
      </View>

      {/* P2: 大卡片网格 — 餐饮 / 牛业 / 角色 三选一, 选法在 QuickActionCardGrid 里 */}
      <QuickActionCardGrid
        userRole={userRole}
        isRestaurantMode={isRestaurantMode}
        factoryId={currentFactoryId}
        onSendIntent={(text, intentCode) => handleSend(text, intentCode ? { intentCode } : undefined)}
        onNavigate={handleCardNavigate}
      />
    </View>
  );

  const canSend = !isLoading && inputText.trim().length > 0;

  return (
    <PaperProvider theme={chatTheme}>
    <SafeAreaView style={[styles.container, tone.screen]} edges={['top']}>
      {/* 消息区域 */}
      <KeyboardAvoidingView
        style={styles.flex1}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        keyboardVerticalOffset={Platform.OS === 'ios' ? 90 : 0}
      >
        <ScrollView
          ref={scrollViewRef}
          testID="ai-chat-thread"
          style={styles.messagesContainer}
          contentContainerStyle={styles.messagesContent}
          onScroll={handleMessageScroll}
          scrollEventThrottle={100}
          onContentSizeChange={scrollToBottom}
        >
          {messages.length === 0 ? renderWelcome() : messages.map(renderMessage)}
        </ScrollView>

        {/* 输入区域 — P1: 语音优先 */}
        <View style={[styles.inputAreaContainer, tone.inputArea]}>
          {/* P1: 语音预览浮层 */}
          {previewText !== '' && (
            <View style={[styles.voicePreview, tone.voicePreview]}>
              <Text variant="bodyLarge" style={[styles.flex1, tone.onSurface]}>{previewText}</Text>
              <TouchableOpacity
                onPress={cancelVoicePreview}
                accessibilityRole="button"
                accessibilityLabel={t('aiChat.cancel')}
                style={styles.voicePreviewCancel}
              >
                <Text variant="labelLarge" style={tone.errorText}>{t('aiChat.cancel')}</Text>
              </TouchableOpacity>
            </View>
          )}
          {/* 输入时的模式预判 (快速/深度) 也是遥测, 只在显式开关下显示 (标准 E2, ⛔ 不用 __DEV__) */}
          {AI_DEBUG_TELEMETRY && detectedMode && showKeyboard && (
            <View testID="ai-input-mode-row" style={styles.inputModeIndicator}>
              <AIModeIndicator mode={detectedMode.mode} size="small" />
              <Text variant="bodySmall" style={[styles.flex1, tone.muted]}>
                {detectedMode.mode === 'deep' ? t('aiChat.deepModeHint') : t('aiChat.quickModeHint')}
              </Text>
            </View>
          )}
          {showKeyboard ? (
            /* 展开态: 文本输入 + 小麦克风 + 发送。
               麦克风与发送键至少一个用主色 —— 之前空输入时整条输入栏全灰, 没有一个活的元素。 */
            <View style={styles.inputContainer}>
              <VoiceMicButton
                onTranscript={handleVoiceResult}
                size={48}
                color={theme.colors.primary}
                activeColor={theme.colors.error}
                disabled={isLoading}
                style={StyleSheet.flatten([styles.roundButton, tone.subtleButton])}
              />
              <View testID="ai-chat-input-frame" style={[styles.inputWrapper, tone.inputWrapper]}>
                <TextInput
                  testID="ai-chat-input"
                  accessibilityLabel={t('aiChat.inputLabel')}
                  style={[styles.input, chatTheme.fonts.bodyLarge, tone.onSurface]}
                  value={inputText}
                  onChangeText={setInputText}
                  placeholder={t('aiChat.inputPlaceholder')}
                  placeholderTextColor={theme.colors.onSurfaceVariant}
                  multiline
                  // web 上 textarea 初始 rows=1, 靠内容长高 (标准 S4: 单行 40-44px)
                  numberOfLines={Platform.OS === 'web' ? 1 : undefined}
                  maxLength={500}
                  editable={!isLoading}
                />
              </View>
              <TouchableOpacity
                testID="ai-chat-send-btn"
                accessibilityRole="button"
                accessibilityLabel={t('aiChat.send')}
                accessibilityState={{ disabled: !canSend }}
                style={[styles.roundButton, canSend ? tone.primaryButton : tone.primaryButtonIdle]}
                onPress={() => handleSend()}
                disabled={!canSend}
              >
                <MaterialCommunityIcons
                  name="send"
                  size={22}
                  color={canSend ? theme.colors.onPrimary : theme.colors.onPrimaryContainer}
                />
              </TouchableOpacity>
            </View>
          ) : (
            /* P1: 默认态 — 大号居中麦克风 */
            <View testID="voice-first-container" style={styles.voiceFirstContainer}>
              <View style={styles.voiceFirstRow}>
                <TouchableOpacity
                  testID="keyboard-toggle-btn"
                  accessibilityRole="button"
                  accessibilityLabel={t('aiChat.switchToKeyboard')}
                  style={[styles.roundButton, tone.subtleButton]}
                  onPress={() => setShowKeyboard(true)}
                >
                  <MaterialCommunityIcons name="keyboard-outline" size={24} color={theme.colors.onSurfaceVariant} />
                </TouchableOpacity>
                <View style={styles.largeMicWrapper}>
                  <View style={[styles.largeMicCircle, tone.primaryButton]}>
                    <VoiceMicButton
                      onTranscript={handleVoiceResult}
                      size={72}
                      color={theme.colors.onPrimary}
                      activeColor={theme.colors.onPrimary}
                      disabled={isLoading}
                    />
                  </View>
                  <Text variant="bodyMedium" style={[styles.voiceHintText, tone.muted]}>
                    {t('aiChat.tapToSpeak')}
                  </Text>
                </View>
                <View style={styles.roundButton} />
              </View>
            </View>
          )}
        </View>
      </KeyboardAvoidingView>
    </SafeAreaView>
    </PaperProvider>
  );
}

// ⚠️ 这里只放布局: 颜色 / 字号全部在组件里走 useTheme() 与 Paper 的 variant (见 tone / variant=)。
const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  flex1: {
    flex: 1,
  },
  messagesContainer: {
    flex: 1,
  },
  messagesContent: {
    padding: 16,
    paddingBottom: 100,
  },
  welcomeContainer: {
    flex: 1,
  },
  // 间距 (标准 S1): 用户消息之后 8 (同一轮用户 → 助手), 助手消息之后 24 (轮与轮之间)
  assistantMessageRow: {
    marginBottom: 24,
  },
  userMessageRow: {
    alignItems: 'flex-end',
    marginBottom: 8,
  },
  // 助手正文列: 占满内容宽 (屏宽 - 32), 没有背景 / 阴影 / 圆角 / 内边距 (标准 L1)
  aiMessageContainer: {
    alignSelf: 'stretch',
  },
  answerText: {
    fontSize: 16,
    lineHeight: 24,
    letterSpacing: 0,
  },
  // 用户气泡 (标准 L2): 四角都是 12, 内边距 12 / 10; maxWidth 在渲染时按窗口宽度算
  userMessageBubble: {
    borderRadius: 12,
    paddingHorizontal: 12,
    paddingVertical: 10,
  },
  loadingText: {
    marginBottom: 8,
  },
  loadingSkeletonGroup: {
    gap: 8,
    // 与条上的 0.6↔1 闪烁相乘, 实际 0.21↔0.35: 看得见但不抢正文
    opacity: 0.35,
  },
  loadingSkeletonBar: {
    height: 14,
    borderRadius: 5,
  },
  errorBanner: {
    marginTop: 8,
    borderRadius: 10,
    borderWidth: 1,
    padding: 12,
    gap: 8,
  },
  retryButton: {
    alignSelf: 'flex-start',
    minHeight: 44,
    minWidth: 88,
    paddingHorizontal: 14,
    borderRadius: 8,
    justifyContent: 'center',
    alignItems: 'center',
  },
  streamingCursor: {
    fontWeight: '300',
  },
  inputContainer: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: 12,
    paddingHorizontal: 16,
  },
  /** 48×48 圆形触控目标 (麦克风 / 发送 / 键盘切换 共用) */
  roundButton: {
    width: 48,
    height: 48,
    borderRadius: 24,
    justifyContent: 'center',
    alignItems: 'center',
  },
  // 输入框 (标准 S4): 单行 44px、圆角 8、内边距 12, 随内容长高到 120
  inputWrapper: {
    flex: 1,
    borderRadius: 8,
    borderWidth: 1,
    marginHorizontal: 10,
    paddingHorizontal: 12,
    paddingVertical: 0,
    minHeight: 44,
    maxHeight: 120,
    justifyContent: 'center',
  },
  input: {
    // 单行: 24 行高 + 2×9 = 42, 加外框上下各 1px 边线 = 44 (paddingVertical 10 会撑到 46, 超出 S4 的 40-44)
    paddingVertical: 9,
    maxHeight: 118,
  },
  // 模式指示器相关样式 (仅显式遥测开关下)
  modeIndicatorRow: {
    flexDirection: 'row',
    alignItems: 'center',
    marginBottom: 4,
    gap: 8,
  },
  inputAreaContainer: {
    borderTopWidth: 1,
  },
  inputModeIndicator: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingHorizontal: 16,
    paddingTop: 8,
    paddingBottom: 4,
    gap: 8,
  },
  // 澄清选项 (标准 F3): 竖排、占满正文列、一个样式; 只给澄清选项用, 追问芯片另有一套
  suggestedActionsContainer: {
    marginTop: 8,
    flexDirection: 'column',
    gap: 8,
  },
  suggestedActionButton: {
    alignSelf: 'stretch',
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 16,
    paddingVertical: 10,
    minHeight: 48,
    justifyContent: 'center',
  },
  // 追问芯片 (标准 S3 / F2): 横排换行、至少 40px 高、最宽 220、标签单行省略
  followUpContainer: {
    marginTop: 8,
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  followUpChip: {
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 14,
    paddingVertical: 8,
    minHeight: 40,
    maxWidth: 220,
    justifyContent: 'center',
  },
  centeredText: {
    textAlign: 'center',
  },
  leftAlignedText: {
    textAlign: 'left',
  },
  suggestedActionDesc: {
    marginTop: 2,
  },
  // P1: Voice-first input styles
  voiceFirstContainer: {
    paddingVertical: 12,
    paddingHorizontal: 16,
  },
  voiceFirstRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
  },
  largeMicWrapper: {
    alignItems: 'center',
  },
  largeMicCircle: {
    width: 72,
    height: 72,
    borderRadius: 36,
    justifyContent: 'center',
    alignItems: 'center',
  },
  voiceHintText: {
    marginTop: 6,
  },
  // P1: Voice preview
  voicePreview: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: 16,
    paddingVertical: 10,
    borderBottomWidth: 1,
  },
  voicePreviewCancel: {
    paddingHorizontal: 12,
    paddingVertical: 6,
    minHeight: 48,
    justifyContent: 'center',
  },
});
