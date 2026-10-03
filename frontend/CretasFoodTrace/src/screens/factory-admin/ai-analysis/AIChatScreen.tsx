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
  ActivityIndicator,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useNavigation, useRoute, RouteProp, CommonActions } from '@react-navigation/native';
import { Text, useTheme } from 'react-native-paper';
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
import type { FAAIStackParamList } from '../../../types/navigation';
import { QuickActionCardGrid } from '../../../components/ai/QuickActionCardGrid';
import { feedbackSounds } from '../../../services/audio/feedbackSounds';
import { MaterialCommunityIcons } from '@expo/vector-icons';
import {
  RESTAURANT_AGENT_RUN_ROUTE,
} from '../../../types/restaurantAgentRun';
import { RestaurantGrossMarginRunCard } from '../../../components/ai/RestaurantGrossMarginRunCard';
import { isRestaurant } from '../../../utils/factoryType';
import { currentShanghaiIsoDate } from '../../../utils/shanghaiDate';

// 建议操作类型
interface SuggestedAction {
  label: string;
  value: string;
  description?: string;
  params?: Record<string, unknown>;
  data?: Record<string, unknown>;
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
  const { t, i18n } = useTranslation('home');
  const scrollViewRef = useRef<ScrollView>(null);
  const theme = useTheme<AppTheme>();
  // 颜色层: 布局在文件底部的 StyleSheet, 颜色全部从这里取 token, 渲染时合并。
  // ⛔ 本文件不许出现 #RRGGBB / fontSize: 10..13 (闸: gates/demoScreensUseThemeTokens)。
  const tone = useMemo(() => ({
    screen: { backgroundColor: theme.colors.background },
    onSurface: { color: theme.colors.onSurface },
    muted: { color: theme.colors.onSurfaceVariant },
    onPrimaryContainer: { color: theme.colors.onPrimaryContainer },
    primaryText: { color: theme.colors.primary },
    errorText: { color: theme.colors.error },
    avatar: { backgroundColor: theme.colors.primaryContainer },
    aiBubble: { backgroundColor: theme.colors.surface, ...theme.custom.shadows.small },
    // 用户气泡用 primaryContainer/onPrimaryContainer (6.83:1); onPrimary on primary 只有 3.24:1, 正文过不了 AA
    userBubble: { backgroundColor: theme.colors.primaryContainer },
    suggestedAction: { backgroundColor: theme.colors.primaryContainer, borderColor: theme.colors.primary },
    inputArea: { backgroundColor: theme.colors.surface, borderTopColor: theme.colors.outlineVariant },
    inputWrapper: { backgroundColor: theme.colors.surfaceVariant, borderColor: theme.colors.outline },
    voicePreview: { backgroundColor: theme.colors.primaryContainer, borderBottomColor: theme.colors.outlineVariant },
    primaryButton: { backgroundColor: theme.colors.primary },
    primaryButtonIdle: { backgroundColor: theme.colors.primaryContainer },
    subtleButton: { backgroundColor: theme.colors.surfaceVariant },
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

  // P1: Voice-first input mode
  const [showKeyboard, setShowKeyboard] = useState(false);
  // P1: Voice preview before auto-send
  const [previewText, setPreviewText] = useState('');
  const previewTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const previewGenerationRef = useRef(0);
  const isMountedRef = useRef(true);
  const sendPendingRef = useRef(false);

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

  // 头像是装饰, 不是按钮: 用 MaterialCommunityIcons 而不是 IconButton (后者 role=button), 并对读屏隐藏。
  const renderAssistantAvatar = () => (
    <View
      style={[styles.aiAvatar, tone.avatar]}
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
    >
      <MaterialCommunityIcons name="robot" size={20} color={theme.colors.onPrimaryContainer} />
    </View>
  );

  // 实时检测当前输入的分析模式
  const detectedMode = useMemo(() => {
    if (!inputText.trim()) return null;
    return detectAnalysisMode(inputText);
  }, [inputText]);

  // 生成唯一消息ID
  const generateMessageId = () => `msg_${Date.now()}_${Math.random().toString(36).substr(2, 9)}`;

  // 滚动到底部
  const scrollToBottom = useCallback(() => {
    setTimeout(() => {
      scrollViewRef.current?.scrollToEnd({ animated: true });
    }, 100);
  }, []);

  // 用于取消流式请求的 AbortController
  const abortControllerRef = useRef<AbortController | null>(null);

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
      await handleSend(originalQuestion, {
        displayText: action.label,
        intentCode: pickedIntent,
        context: { clarificationSelection: true, clarificationCandidates: candidates },
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

    // React state updates do not synchronously guard another press in this render.
    sendPendingRef.current = true;
    try {
      setInputText('');
      setIsLoading(true);
      setStreamStatus(null);
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

          // 如果有结果文本且没有流式内容，显示结果
          if (replyMessage && !streamedContent) {
            streamedContent = replyMessage;
          }

          // 提取建议操作
          let suggestedActions: SuggestedAction[] = [];
          const status = result.status as string | undefined;

          // NEED_MORE_INFO: 优先使用 clarificationQuestions 构建友好回复
          if (status === 'NEED_MORE_INFO' && Array.isArray(result.clarificationQuestions)
              && (result.clarificationQuestions as string[]).length > 0) {
            const questions = result.clarificationQuestions as string[];
            replyMessage = `为了完成操作，还需要以下信息：\n\n${questions.map((q, i) => `${i + 1}. ${q}`).join('\n')}\n\n请直接告诉我，或点击下方选项。`;
            streamedContent = replyMessage;
          }

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

          // 更新消息
          setMessages((prev) =>
            prev.map((msg) =>
              msg.id === assistantMessageId
                ? {
                    ...msg,
                    content: streamedContent || replyMessage || t('aiChat.defaultReply'),
                    intentCode: (result.intentCode as string) || msg.intentCode,
                    intentCategory: result.intentCategory as string | undefined,
                    suggestedActions: suggestedActions.length > 0 ? suggestedActions : undefined,
                    foodKbMetadata: foodKbMeta,
                    richData,
                    agentRun,
                    agentRunFactoryId: agentRun ? factoryId : undefined,
                    agentRunOwnerUserId: agentRun ? String(user?.id ?? '') : undefined,
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
                    content: message || t('aiChat.networkError'),
                    isLoading: false,
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
      if (sendOptions?.intentCode || sendOptions?.context || recentTurns.length > 0 || sessionId) {
        await aiApiClient.executeIntentStream(messageText, callbacks, factoryId, entityType, {
          ...(sendOptions?.intentCode ? { intentCode: sendOptions.intentCode } : {}),
          ...((sendOptions?.context || recentTurns.length > 0) ? {
            context: { ...(sendOptions?.context || {}), ...(recentTurns.length > 0 ? { recentTurns } : {}) },
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
        const errorMessage = error instanceof Error ? error.message : t('aiChat.networkError');
        setMessages((prev) =>
          prev.map((msg) =>
            msg.id === assistantMessageId
              ? { ...msg, content: errorMessage, isLoading: false }
              : msg
          )
        );
      }
      setStreamStatus(null);
      setIsLoading(false);
    } finally {
      if (abortControllerRef.current === controller) abortControllerRef.current = null;
      if (isCurrentStream()) {
        sendPendingRef.current = false;
        setIsLoading(false);
      }
    }
  };

  // 渲染消息
  const renderMessage = (message: Message) => {
    const isUser = message.role === 'user';
    const timestampText = message.timestamp.toLocaleTimeString(i18n.language, {
      hour: '2-digit',
      minute: '2-digit',
    });

    // 流式加载状态：无内容时显示思考动画+进度状态，有内容时显示内容+光标
    if (message.isLoading && !message.content) {
      return (
        <View key={message.id} style={styles.messageRow}>
          {renderAssistantAvatar()}
          <View style={[styles.aiMessageBubble, styles.loadingBubble, tone.aiBubble]}>
            <ActivityIndicator size="small" color={theme.colors.primary} />
            <Text variant="bodyMedium" style={[styles.loadingText, tone.muted]}>
              {streamStatus || t('aiChat.thinking')}
            </Text>
          </View>
        </View>
      );
    }

    if (isUser) {
      return (
        <View key={message.id} style={styles.userMessageRow}>
          <View style={[styles.userMessageBubble, tone.userBubble]}>
            <Text variant="bodyMedium" style={tone.onPrimaryContainer}>{message.content}</Text>
          </View>
          <Text variant="bodySmall" style={[styles.timestamp, tone.muted]}>{timestampText}</Text>
        </View>
      );
    }

    return (
      <View key={message.id} style={styles.messageRow}>
        {renderAssistantAvatar()}
        <View style={styles.aiMessageContainer}>
          {/* 分析模式 + 耗时是工程遥测, 只在 __DEV__ 下给开发者看, 不露给老板 */}
          {__DEV__ && message.mode && !message.isLoading && (
            <View testID="ai-telemetry-row" style={styles.modeIndicatorRow}>
              <AIModeIndicator mode={message.mode} size="small" />
              {message.responseTimeMs ? (
                <Text variant="bodySmall" style={tone.muted}>
                  {(message.responseTimeMs / 1000).toFixed(1)}s
                </Text>
              ) : null}
            </View>
          )}
          <View style={[styles.aiMessageBubble, tone.aiBubble]}>
            {/* 2026-07-24: resolver 答案已 markdown 化 (加粗/列表/引用块) —
                纯 Text 会露出裸 ** 星号, 完成态走 MarkdownRenderer;
                流式中仍用 Text (逐字追加 + 光标, markdown 半截标记无意义)。
                ⚠️ 两条路都保留 `\n`: 后端 Tool 让「每头牛一行」, 这里不许把换行吞掉。 */}
            {message.isLoading ? (
              <Text variant="bodyMedium" style={tone.onSurface}>
                {message.content}
                {message.content ? (
                  <Text variant="bodyMedium" style={[styles.streamingCursor, tone.primaryText]}>|</Text>
                ) : null}
              </Text>
            ) : (
              <MarkdownRenderer content={message.content} />
            )}
            {/* 结构化数据富组件渲染（表格/卡片/统计） */}
            {!message.isLoading && message.richData && (
              <RichContentRenderer data={message.richData} />
            )}
          </View>
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
          {/* 显示建议操作按钮 (当需要澄清时) */}
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
                  <Text variant="labelLarge" style={[styles.centeredText, tone.onPrimaryContainer]}>
                    {action.label}
                  </Text>
                  {/* 只有当 description 与 label 不同时才显示 */}
                  {action.description && action.description !== action.label && (
                    <Text variant="bodySmall" style={[styles.centeredText, styles.suggestedActionDesc, tone.muted]}>
                      {action.description}
                    </Text>
                  )}
                </TouchableOpacity>
              ))}
            </View>
          )}
          {/* 显示反馈组件（仅食品知识类意图） */}
          {!message.isLoading &&
            message.intentCategory === 'FOOD_KNOWLEDGE' &&
            message.userQuestion && (
              <FeedbackWidget
                queryId={message.id}
                question={message.userQuestion}
                answer={message.content}
                sessionId={message.sessionIdForReply || sessionId || undefined}
                intentCode={message.intentCode}
                foodKbMetadata={message.foodKbMetadata}
              />
            )}
          {!message.isLoading && (
            <Text variant="bodySmall" style={[styles.timestamp, tone.muted]}>{timestampText}</Text>
          )}
        </View>
      </View>
    );
  };

  // 渲染欢迎消息 — P2: 使用大卡片网格替代小文字快捷问题
  const renderWelcome = () => (
    <View style={styles.welcomeContainer}>
      {/* AI 欢迎消息 */}
      <View style={styles.messageRow}>
        {renderAssistantAvatar()}
        <View style={[styles.aiMessageBubble, tone.aiBubble]}>
          <Text variant="bodyMedium" style={tone.onSurface}>
            {t(isRestaurantMode ? 'aiChat.restaurantWelcome' : 'aiChat.welcome')}
          </Text>
          <Text variant="bodyMedium" style={[tone.onSurface, { marginTop: theme.custom.spacing.s }]}>
            {t(isRestaurantMode ? 'aiChat.restaurantCanHelp' : 'aiChat.canHelp')}
          </Text>
        </View>
      </View>

      {/* P2: 大卡片网格 — 餐饮 / 牛业 / 角色 三选一, 选法在 QuickActionCardGrid 里 */}
      <QuickActionCardGrid
        userRole={userRole}
        isRestaurantMode={isRestaurantMode}
        factoryId={currentFactoryId}
        onSendIntent={(text) => handleSend(text)}
        onNavigate={handleCardNavigate}
      />
    </View>
  );

  const canSend = !isLoading && inputText.trim().length > 0;

  return (
    <SafeAreaView style={[styles.container, tone.screen]} edges={['top']}>
      {/* 消息区域 */}
      <KeyboardAvoidingView
        style={styles.flex1}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        keyboardVerticalOffset={Platform.OS === 'ios' ? 90 : 0}
      >
        <ScrollView
          ref={scrollViewRef}
          style={styles.messagesContainer}
          contentContainerStyle={styles.messagesContent}
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
          {/* 输入时的模式预判 (快速/深度) 也是遥测, 只在 __DEV__ 下显示 */}
          {__DEV__ && detectedMode && showKeyboard && (
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
              <View style={[styles.inputWrapper, tone.inputWrapper]}>
                <TextInput
                  testID="ai-chat-input"
                  accessibilityLabel={t('aiChat.inputLabel')}
                  style={[styles.input, theme.fonts.bodyLarge, tone.onSurface]}
                  value={inputText}
                  onChangeText={setInputText}
                  placeholder={t('aiChat.inputPlaceholder')}
                  placeholderTextColor={theme.colors.onSurfaceVariant}
                  multiline
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
  messageRow: {
    flexDirection: 'row',
    marginBottom: 16,
  },
  userMessageRow: {
    alignItems: 'flex-end',
    marginBottom: 16,
  },
  aiAvatar: {
    width: 36,
    height: 36,
    borderRadius: 18,
    marginRight: 10,
    justifyContent: 'center',
    alignItems: 'center',
  },
  aiMessageContainer: {
    flex: 1,
  },
  aiMessageBubble: {
    borderRadius: 12,
    borderTopLeftRadius: 0,
    padding: 12,
    maxWidth: '85%',
  },
  loadingBubble: {
    flexDirection: 'row',
    alignItems: 'center',
  },
  userMessageBubble: {
    borderRadius: 12,
    borderTopRightRadius: 0,
    padding: 12,
    maxWidth: '75%',
  },
  timestamp: {
    marginTop: 4,
  },
  loadingText: {
    marginLeft: 8,
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
  inputWrapper: {
    flex: 1,
    borderRadius: 20,
    borderWidth: 1,
    marginHorizontal: 10,
    paddingHorizontal: 16,
    paddingVertical: 8,
    minHeight: 48,
    maxHeight: 100,
    justifyContent: 'center',
  },
  input: {
    maxHeight: 80,
  },
  // 模式指示器相关样式 (仅 __DEV__)
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
  // 建议操作按钮样式
  suggestedActionsContainer: {
    marginTop: 8,
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
  },
  suggestedActionButton: {
    borderWidth: 1,
    borderRadius: 8,
    paddingHorizontal: 14,
    paddingVertical: 14,
    minWidth: 80,
    minHeight: 48,
    justifyContent: 'center',
  },
  centeredText: {
    textAlign: 'center',
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
