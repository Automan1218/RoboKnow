import { useWebSocket } from '@vueuse/core';
import { fetchSessions, createSession, switchSession, deleteSession } from '@/service/api/conversation';

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

  const wsProtocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  const wsUrl = `${wsProtocol}//${window.location.host}/proxy-ws/chat/${store.token}`;

  const {
    status: wsStatus,
    data: wsData,
    send: _wsSend,
    open: wsOpen,
    close: wsClose
  } = useWebSocket(wsUrl, {
    autoReconnect: true
  });

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
