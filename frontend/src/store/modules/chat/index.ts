import { useWebSocket } from '@vueuse/core';
import { createSession, deleteSession, fetchSessions, switchSession } from '@/service/api/conversation';

const emptyUsageSummary = (): Api.AiUsage.Summary => ({
  promptTokens: 0,
  completionTokens: 0,
  totalTokens: 0,
  requestCount: 0
});

function diffUsage(current: Api.AiUsage.Summary, baseline: Api.AiUsage.Summary | null) {
  if (!baseline) return emptyUsageSummary();

  return {
    promptTokens: Math.max(0, current.promptTokens - baseline.promptTokens),
    completionTokens: Math.max(0, current.completionTokens - baseline.completionTokens),
    totalTokens: Math.max(0, current.totalTokens - baseline.totalTokens),
    requestCount: Math.max(0, current.requestCount - baseline.requestCount)
  };
}

export const useChatStore = defineStore(SetupStoreId.Chat, () => {
  const conversationId = ref<string>('');
  const input = ref<Api.Chat.Input>({ message: '' });

  const list = ref<Api.Chat.Message[]>([]);
  const usageLoading = ref(false);
  const totalUsage = ref<Api.AiUsage.Summary>(emptyUsageSummary());
  const currentTurnUsage = ref<Api.AiUsage.Summary | null>(null);
  const sessionUsage = ref<Api.AiUsage.Summary>(emptyUsageSummary());
  const currentTurnBaseline = ref<Api.AiUsage.Summary | null>(null);
  const sessionBaseline = ref<Api.AiUsage.Summary | null>(null);

  // ── Multi-session state ──────────────────────────────────────────────────
  const sessions = ref<Api.Chat.Session[]>([]);
  const activeConvId = ref<string>('');
  const sessionsLoading = ref(false);

  const store = useAuthStore();

  // WS URL 必须跟随当前登录 token：传响应式 URL，vueuse 的 autoConnect 会在 token
  // 变化时自动断开旧连接并用新 URL 重连，避免切换账号后仍以旧账号身份聊天（越权）。
  // token 为空（未登录/已登出）时 URL 为 undefined，useWebSocket 不会发起连接。
  const wsUrl = computed(() => (store.token ? `/proxy-ws/chat/${store.token}` : undefined));

  const {
    status: wsStatus,
    data: wsData,
    send: _wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(wsUrl, {
    autoReconnect: true
  });

  // 登出（含切换账号前的登出）时清空上一个账号的会话与用量状态，防止跨账号串数据。
  // 仅在 token 清空时清理：无感刷新（setToken）只换 token 不登出，不能打断进行中的会话。
  watch(
    () => store.token,
    token => {
      if (token) return;
      list.value = [];
      sessions.value = [];
      activeConvId.value = '';
      conversationId.value = '';
      input.value = { message: '' };
      totalUsage.value = emptyUsageSummary();
      sessionUsage.value = emptyUsageSummary();
      currentTurnUsage.value = null;
      currentTurnBaseline.value = null;
      sessionBaseline.value = null;
    }
  );

  /** Send JSON message with convId so backend routes to the correct session */
  function wsSend(message: string) {
    const payload = JSON.stringify({ message, convId: activeConvId.value || undefined });
    _wsSend(payload);
  }

  const scrollToBottom = ref<null | (() => void)>(null);
  const previewFileName = ref<string>('');

  // ── Session management ───────────────────────────────────────────────────

  async function loadSessions() {
    sessionsLoading.value = true;
    const { error, data } = await fetchSessions();
    if (!error && data) {
      sessions.value = data;
      if (!activeConvId.value && data.length > 0) {
        activeConvId.value = data[0].convId;
      }
    }
    sessionsLoading.value = false;
  }

  async function newSession() {
    const { error, data } = await createSession();
    if (!error && data) {
      activeConvId.value = data.convId;
      list.value = [];
      await loadSessions();
    }
  }

  async function switchToSession(convId: string) {
    if (convId === activeConvId.value) return;
    const { error } = await switchSession(convId);
    if (!error) {
      activeConvId.value = convId;
      list.value = [];
    }
  }

  async function removeSession(convId: string) {
    const { error } = await deleteSession(convId);
    if (!error) {
      await loadSessions();
      // If we deleted the active session, switch to first available
      if (convId === activeConvId.value) {
        if (sessions.value.length > 0) {
          await switchToSession(sessions.value[0].convId);
        } else {
          await newSession();
        }
      }
    }
  }

  // ── Usage ────────────────────────────────────────────────────────────────

  async function fetchUsageSummary() {
    const { error, data } = await request<Api.AiUsage.Response>({ url: 'ai/usage' });
    if (error) return null;

    return data.summary;
  }

  async function initUsageBaseline() {
    const summary = await fetchUsageSummary();
    if (!summary) return;

    totalUsage.value = summary;
    sessionBaseline.value = summary;
    sessionUsage.value = emptyUsageSummary();
  }

  async function prepareCurrentTurnUsage() {
    usageLoading.value = true;
    currentTurnUsage.value = null;

    const summary = await fetchUsageSummary();
    currentTurnBaseline.value = summary ?? totalUsage.value;
    if (!sessionBaseline.value) {
      sessionBaseline.value = currentTurnBaseline.value;
    }
  }

  async function refreshUsage() {
    const summary = await fetchUsageSummary();
    usageLoading.value = false;
    if (!summary) return;

    totalUsage.value = summary;
    currentTurnUsage.value = diffUsage(summary, currentTurnBaseline.value);
    sessionUsage.value = diffUsage(summary, sessionBaseline.value);
  }

  return {
    input,
    conversationId,
    list,
    wsStatus,
    wsData,
    wsSend,
    wsRawSend: _wsSend,
    wsOpen,
    wsClose,
    scrollToBottom,
    previewFileName,
    usageLoading,
    totalUsage,
    currentTurnUsage,
    sessionUsage,
    initUsageBaseline,
    prepareCurrentTurnUsage,
    refreshUsage,
    // Session exports
    sessions,
    activeConvId,
    sessionsLoading,
    loadSessions,
    newSession,
    switchToSession,
    removeSession
  };
});
