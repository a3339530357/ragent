import { create } from "zustand";
import { toast } from "sonner";

import type {
  CompletionPayload,
  FeedbackValue,
  Message,
  MessageDeltaPayload,
  Session
} from "@/types";
import {
  listMessages,
  listSessions,
  deleteSession as deleteSessionRequest,
  renameSession as renameSessionRequest
} from "@/services/sessionService";
import {
  stopTask,
  submitFeedback,
  cancelFeedback,
  generateRecommendedQuestions
} from "@/services/chatService";
import { buildQuery } from "@/utils/helpers";
import { createStreamResponse } from "@/hooks/useStreamResponse";
import { storage } from "@/utils/storage";

interface ChatState {
  sessions: Session[];
  currentSessionId: string | null;
  messages: Message[];
  isLoading: boolean;
  sessionsLoaded: boolean;
  inputFocusKey: number;
  isStreaming: boolean;
  isCreatingNew: boolean;
  deepThinkingEnabled: boolean;
  thinkingStartAt: number | null;
  streamTaskId: string | null;
  streamAbort: (() => void) | null;
  streamingMessageId: string | null;
  cancelRequested: boolean;
  openedSourceMessageId: string | null;
  // 展开推荐面板后需滚入视口的消息；每次请求都是新对象，供 MessageList 一次性响应
  recommendReveal: { id: string } | null;
  fetchSessions: () => Promise<void>;
  createSession: () => Promise<string>;
  deleteSession: (sessionId: string) => Promise<void>;
  renameSession: (sessionId: string, title: string) => Promise<void>;
  selectSession: (sessionId: string) => Promise<void>;
  updateSessionTitle: (sessionId: string, title: string) => void;
  setDeepThinkingEnabled: (enabled: boolean) => void;
  sendMessage: (content: string) => Promise<void>;
  cancelGeneration: () => void;
  retryMessage: (assistantId: string) => Promise<void>;
  appendStreamContent: (delta: string) => void;
  appendThinkingContent: (delta: string) => void;
  submitFeedback: (messageId: string, feedback: FeedbackValue) => Promise<void>;
  toggleSourcesPanel: (messageId: string) => void;
  openSourcesPanel: (messageId: string) => void;
  closeSourcesPanel: () => void;
  loadRecommended: (messageId: string) => Promise<void>;
  toggleRecommended: (messageId: string) => void;
}

function mapVoteToFeedback(vote?: number | null): FeedbackValue {
  if (vote === 1) return "like";
  if (vote === -1) return "dislike";
  return null;
}

function upsertSession(sessions: Session[], next: Session) {
  const index = sessions.findIndex((session) => session.id === next.id);
  const updated = [...sessions];
  if (index >= 0) {
    updated[index] = { ...sessions[index], ...next };
  } else {
    updated.unshift(next);
  }
  return updated.sort((a, b) => {
    const timeA = a.lastTime ? new Date(a.lastTime).getTime() : 0;
    const timeB = b.lastTime ? new Date(b.lastTime).getTime() : 0;
    return timeB - timeA;
  });
}

function computeThinkingDuration(startAt?: number | null) {
  if (!startAt) return undefined;
  const seconds = Math.round((Date.now() - startAt) / 1000);
  return Math.max(1, seconds);
}

// 点了停止后等后端确认的最长时间；超时就在本地断开连接并按已停止处理，避免按钮一直卡在「停止中」
const CANCEL_FALLBACK_MS = 6000;

/**
 * 把流式请求的异常翻译成用户能看懂、知道下一步怎么做的话
 * 后端通过 error 事件推来的文案原样保留，只翻译前端自己抛出的网络/HTTP 错误
 */
export function describeStreamError(error: Error): string {
  const raw = (error?.message || "").trim();
  const httpMatch = raw.match(/SSE 请求失败（(\d+)）/);
  if (httpMatch) {
    const code = Number(httpMatch[1]);
    if (code === 401 || code === 403) return "登录已失效，请重新登录后重试";
    if (code === 429) return "请求太频繁，请稍后重试";
    if (code >= 500) return `服务暂时出错（${code}），请稍后重试`;
    return `请求失败（${code}）`;
  }
  if (error?.name === "TypeError" || /failed to fetch|network|load failed|流式响应为空/i.test(raw)) {
    return "网络连接中断，请检查网络后重试";
  }
  return raw || "生成失败，请重试";
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

export const useChatStore = create<ChatState>((set, get) => ({
  sessions: [],
  currentSessionId: null,
  messages: [],
  isLoading: false,
  sessionsLoaded: false,
  inputFocusKey: 0,
  isStreaming: false,
  isCreatingNew: false,
  deepThinkingEnabled: false,
  thinkingStartAt: null,
  streamTaskId: null,
  streamAbort: null,
  streamingMessageId: null,
  cancelRequested: false,
  openedSourceMessageId: null,
  recommendReveal: null,
  fetchSessions: async () => {
    set({ isLoading: true });
    try {
      const data = await listSessions();
      const sessions = data
        .map((item) => ({
        id: item.conversationId,
        title: item.title || "新对话",
        lastTime: item.lastTime
        }))
        .sort((a, b) => {
          const timeA = a.lastTime ? new Date(a.lastTime).getTime() : 0;
          const timeB = b.lastTime ? new Date(b.lastTime).getTime() : 0;
          return timeB - timeA;
        });
      set({ sessions });
    } catch (error) {
      toast.error((error as Error).message || "加载会话失败");
    } finally {
      set({ isLoading: false, sessionsLoaded: true });
    }
  },
  createSession: async () => {
    const state = get();
    if (state.messages.length === 0 && !state.currentSessionId) {
      set({
        isCreatingNew: true,
        isLoading: false,
        thinkingStartAt: null,
        deepThinkingEnabled: false,
        openedSourceMessageId: null
      });
      return "";
    }
    if (state.isStreaming) {
      get().cancelGeneration();
    }
    set({
      currentSessionId: null,
      messages: [],
      isStreaming: false,
      isLoading: false,
      isCreatingNew: true,
      deepThinkingEnabled: false,
      thinkingStartAt: null,
      streamTaskId: null,
      streamAbort: null,
      streamingMessageId: null,
      cancelRequested: false,
      openedSourceMessageId: null
    });
    return "";
  },
  deleteSession: async (sessionId) => {
    try {
      await deleteSessionRequest(sessionId);
      set((state) => ({
        sessions: state.sessions.filter((session) => session.id !== sessionId),
        messages: state.currentSessionId === sessionId ? [] : state.messages,
        currentSessionId: state.currentSessionId === sessionId ? null : state.currentSessionId,
        openedSourceMessageId:
          state.currentSessionId === sessionId ? null : state.openedSourceMessageId
      }));
      toast.success("删除成功");
    } catch (error) {
      toast.error((error as Error).message || "删除会话失败");
    }
  },
  renameSession: async (sessionId, title) => {
    const nextTitle = title.trim();
    if (!nextTitle) return;
    try {
      await renameSessionRequest(sessionId, nextTitle);
      set((state) => ({
        sessions: state.sessions.map((session) =>
          session.id === sessionId ? { ...session, title: nextTitle } : session
        )
      }));
      toast.success("已重命名");
    } catch (error) {
      toast.error((error as Error).message || "重命名失败");
    }
  },
  selectSession: async (sessionId) => {
    if (!sessionId) return;
    if (get().currentSessionId === sessionId && get().messages.length > 0) return;
    if (get().isStreaming) {
      get().cancelGeneration();
    }
    set({
      isLoading: true,
      currentSessionId: sessionId,
      isCreatingNew: false,
      thinkingStartAt: null,
      openedSourceMessageId: null
    });
    try {
      const data = await listMessages(sessionId);
      if (get().currentSessionId !== sessionId) {
        return;
      }
      const mapped: Message[] = data.map((item) => ({
        id: String(item.id),
        role: item.role === "assistant" ? "assistant" : "user",
        content: item.content,
        thinking: item.thinkingContent || undefined,
        thinkingDuration: item.thinkingDuration || undefined,
        isDeepThinking: Boolean(item.thinkingContent),
        createdAt: item.createTime,
        feedback: mapVoteToFeedback(item.vote),
        status: "done",
        sources: item.sources || undefined,
        recommended: item.recommendedQuestions ?? undefined,
        recommendedState: Array.isArray(item.recommendedQuestions) ? "ready" : undefined,
        messageStatus: item.messageStatus ?? "NORMAL"
      }));
      set({ messages: mapped });
    } catch (error) {
      toast.error((error as Error).message || "加载消息失败");
    } finally {
      if (get().currentSessionId === sessionId) {
        set({
          isLoading: false,
          isStreaming: false,
          streamTaskId: null,
          streamAbort: null,
          streamingMessageId: null,
          cancelRequested: false
        });
      }
    }
  },
  updateSessionTitle: (sessionId, title) => {
    set((state) => ({
      sessions: state.sessions.map((session) =>
        session.id === sessionId ? { ...session, title } : session
      )
    }));
  },
  setDeepThinkingEnabled: (enabled) => {
    set({ deepThinkingEnabled: enabled });
  },
  sendMessage: async (content) => {
    const trimmed = content.trim();
    if (!trimmed) return;
    if (get().isStreaming) return;
    const deepThinkingEnabled = get().deepThinkingEnabled;
    const inputFocusKey = Date.now();

    const userMessage: Message = {
      id: `user-${Date.now()}`,
      role: "user",
      content: trimmed,
      status: "done",
      createdAt: new Date().toISOString()
    };
    const assistantId = `assistant-${Date.now()}`;
    const assistantMessage: Message = {
      id: assistantId,
      role: "assistant",
      content: "",
      thinking: deepThinkingEnabled ? "" : undefined,
      isDeepThinking: deepThinkingEnabled,
      isThinking: deepThinkingEnabled,
      status: "streaming",
      feedback: null,
      createdAt: new Date().toISOString()
    };

    set((state) => ({
      // 新问题发出即收起所有历史消息的推荐面板 保持焦点在最新一轮问答
      messages: [
        ...state.messages.map((message) =>
          message.recommendedOpen ? { ...message, recommendedOpen: false } : message
        ),
        userMessage,
        assistantMessage
      ],
      isStreaming: true,
      streamingMessageId: assistantId,
      thinkingStartAt: null,
      inputFocusKey,
      streamTaskId: null,
      cancelRequested: false
    }));

    const conversationId = get().currentSessionId;
    const query = buildQuery({
      question: trimmed,
      conversationId: conversationId || undefined,
      deepThinking: deepThinkingEnabled ? true : undefined
    });
    const url = `${API_BASE_URL}/rag/v3/chat${query}`;
    const token = storage.getToken();

    const handlers = {
      onMeta: (payload: { conversationId: string; taskId: string }) => {
        if (get().streamingMessageId !== assistantId) return;
        const nextId = payload.conversationId || get().currentSessionId;
        if (!nextId) return;
        const lastTime = new Date().toISOString();
        const existing = get().sessions.find((session) => session.id === nextId);
        set((state) => ({
          currentSessionId: nextId,
          isCreatingNew: false,
          streamTaskId: payload.taskId,
          sessions: upsertSession(state.sessions, {
            id: nextId,
            title: existing?.title || "新对话",
            lastTime
          })
        }));
        if (get().cancelRequested) {
          stopTask(payload.taskId).catch(() => null);
        }
      },
      onMessage: (payload: MessageDeltaPayload) => {
        if (!payload || typeof payload !== "object") return;
        if (payload.type !== "response") return;
        get().appendStreamContent(payload.delta);
      },
      onThinking: (payload: MessageDeltaPayload) => {
        if (!payload || typeof payload !== "object") return;
        if (payload.type !== "think") return;
        get().appendThinkingContent(payload.delta);
      },
      onReject: (payload: MessageDeltaPayload) => {
        if (!payload || typeof payload !== "object") return;
        get().appendStreamContent(payload.delta);
      },
      onFinish: (payload: CompletionPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (!payload) return;
        if (payload.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
        const currentId = get().currentSessionId;
        if (currentId) {
          const lastTime = new Date().toISOString();
          const existingTitle =
            get().sessions.find((session) => session.id === currentId)?.title || "新对话";
          const nextTitle = payload.title || existingTitle;
          set((state) => ({
            sessions: upsertSession(state.sessions, {
              id: currentId,
              title: nextTitle,
              lastTime
            })
          }));
        }
        if (payload.messageId) {
          set((state) => ({
            messages: state.messages.map((message) =>
              message.id === state.streamingMessageId
                ? {
                    ...message,
                    id: String(payload.messageId),
                    status: "done",
                    isThinking: false,
                    sources: payload.sources ?? message.sources,
                    messageStatus: payload.messageStatus ?? "NORMAL",
                    thinkingDuration:
                      message.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
                  }
                : message
            )
          }));
        } else {
          set((state) => ({
            messages: state.messages.map((message) =>
              message.id === state.streamingMessageId
                ? {
                    ...message,
                    status: "done",
                    isThinking: false,
                    sources: payload.sources ?? message.sources,
                    messageStatus: payload.messageStatus ?? "NORMAL",
                    thinkingDuration:
                      message.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
                  }
                : message
            )
          }));
        }
      },
      onCancel: (payload: CompletionPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (payload?.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
        set((state) => ({
          messages: state.messages.map((message) => {
            if (message.id !== state.streamingMessageId) return message;
            // 「已停止」由消息下方的状态标签展示，不再拼进正文，避免复制时一并带走
            const nextId = payload?.messageId ? String(payload.messageId) : message.id;
            return {
              ...message,
              id: nextId,
              status: "cancelled",
              isThinking: false,
              sources: payload?.sources ?? message.sources,
              messageStatus: payload?.messageStatus ?? "INTERRUPTED",
              thinkingDuration:
                message.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
            };
          }),
          isStreaming: false,
          thinkingStartAt: null,
          streamTaskId: null,
          streamAbort: null,
          streamingMessageId: null,
          cancelRequested: false
        }));
      },
      onDone: () => {
        if (get().streamingMessageId !== assistantId) return;
        set({
          isStreaming: false,
          thinkingStartAt: null,
          streamTaskId: null,
          streamAbort: null,
          streamingMessageId: null,
          cancelRequested: false
        });
      },
      onTitle: (payload: { title: string }) => {
        if (get().streamingMessageId !== assistantId) return;
        if (payload?.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
      },
      onError: (error: Error) => {
        if (get().streamingMessageId !== assistantId) return;
        set((state) => ({
          isStreaming: false,
          thinkingStartAt: null,
          streamTaskId: null,
          streamAbort: null,
          cancelRequested: false,
          messages: state.messages.map((message) =>
            message.id === state.streamingMessageId
              ? {
                  ...message,
                  status: "error",
                  errorMessage: describeStreamError(error),
                  isThinking: false,
                  thinkingDuration:
                    message.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
                }
              : message
          )
        }));
        // 失败原因和「重试」按钮直接显示在这条回答下方，不再额外弹全局提示
      }
    };

    const { start, cancel } = createStreamResponse(
      {
        url,
        headers: token ? { Authorization: token } : undefined,
        retryCount: 1
      },
      handlers
    );

    set({ streamAbort: cancel });

    try {
      await start();
    } catch (error) {
      if ((error as Error).name === "AbortError") {
        return;
      }
      handlers.onError?.(error as Error);
    } finally {
      if (get().streamingMessageId === assistantId) {
        set({
          isStreaming: false,
          streamTaskId: null,
          streamAbort: null,
          streamingMessageId: null,
          cancelRequested: false
        });
      }
    }
  },
  cancelGeneration: () => {
    const { isStreaming, streamTaskId, cancelRequested, streamingMessageId } = get();
    if (!isStreaming || cancelRequested) return;
    // cancelRequested 同时驱动输入区的「停止中」状态：点下即有反馈，且防重复点击
    set({ cancelRequested: true });
    if (streamTaskId) {
      stopTask(streamTaskId).catch(() => null);
    }
    // 兜底：后端迟迟不确认（停止接口失败、任务号一直没下发）时本地断开，按已停止收尾
    const targetId = streamingMessageId;
    window.setTimeout(() => {
      const state = get();
      if (!state.isStreaming || !state.cancelRequested || state.streamingMessageId !== targetId) {
        return;
      }
      state.streamAbort?.();
      set((current) => ({
        messages: current.messages.map((message) =>
          message.id === targetId
            ? {
                ...message,
                status: "cancelled",
                isThinking: false,
                messageStatus: "INTERRUPTED",
                thinkingDuration:
                  message.thinkingDuration ?? computeThinkingDuration(current.thinkingStartAt)
              }
            : message
        ),
        isStreaming: false,
        thinkingStartAt: null,
        streamTaskId: null,
        streamAbort: null,
        streamingMessageId: null,
        cancelRequested: false
      }));
    }, CANCEL_FALLBACK_MS);
  },
  retryMessage: async (assistantId) => {
    const { messages, isStreaming } = get();
    if (isStreaming) return;
    const index = messages.findIndex((message) => message.id === assistantId);
    if (index <= 0) return;
    const question = messages[index - 1];
    if (question.role !== "user" || !question.content.trim()) return;
    // 把失败的这一轮从界面上撤掉，再用同一个问题重新发起；sendMessage 会重新追加这一问一答
    set((state) => ({
      messages: state.messages.filter((_, i) => i !== index && i !== index - 1)
    }));
    await get().sendMessage(question.content);
  },
  appendStreamContent: (delta) => {
    if (!delta) return;
    set((state) => {
      const shouldFinalizeThinking = state.thinkingStartAt != null;
      const duration = computeThinkingDuration(state.thinkingStartAt);
      return {
        thinkingStartAt: shouldFinalizeThinking ? null : state.thinkingStartAt,
        messages: state.messages.map((message) => {
          if (message.id !== state.streamingMessageId) return message;
          if (message.status === "cancelled" || message.status === "error") return message;
          return {
            ...message,
            content: message.content + delta,
            isThinking: shouldFinalizeThinking ? false : message.isThinking,
            thinkingDuration:
              shouldFinalizeThinking && !message.thinkingDuration ? duration : message.thinkingDuration
          };
        })
      };
    });
  },
  appendThinkingContent: (delta) => {
    if (!delta) return;
    set((state) => ({
      thinkingStartAt: state.thinkingStartAt ?? Date.now(),
      messages: state.messages.map((message) =>
        message.id === state.streamingMessageId &&
        message.status !== "cancelled" &&
        message.status !== "error"
          ? {
              ...message,
              thinking: `${message.thinking ?? ""}${delta}`,
              isThinking: true
            }
          : message
      )
    }));
  },
  submitFeedback: async (messageId, feedback) => {
    const vote = feedback === "like" ? 1 : feedback === "dislike" ? -1 : null;
    const prev = get().messages.find((message) => message.id === messageId)?.feedback ?? null;
    set((state) => ({
      messages: state.messages.map((message) =>
        message.id === messageId ? { ...message, feedback } : message
      )
    }));
    try {
      if (vote === null) {
        await cancelFeedback(messageId);
        toast.success("取消成功");
        return;
      }
      await submitFeedback(messageId, vote);
      toast.success(feedback === "like" ? "点赞成功" : "点踩成功");
    } catch (error) {
      set((state) => ({
        messages: state.messages.map((message) =>
          message.id === messageId ? { ...message, feedback: prev } : message
        )
      }));
      toast.error((error as Error).message || (vote === null ? "取消反馈失败" : "反馈保存失败"));
    }
  },
  toggleSourcesPanel: (messageId) => {
    set((state) => ({
      openedSourceMessageId: state.openedSourceMessageId === messageId ? null : messageId
    }));
  },
  openSourcesPanel: (messageId) => set({ openedSourceMessageId: messageId }),
  closeSourcesPanel: () => set({ openedSourceMessageId: null }),
  loadRecommended: async (messageId) => {
    const target = get().messages.find((message) => message.id === messageId);
    // loading/ready 直接返回：避免同一消息重复请求
    if (!target || target.recommendedState === "loading" || target.recommendedState === "ready") {
      return;
    }
    // 纯手动触发：点击即展开并露出骨架反馈
    set((state) => ({
      messages: state.messages.map((message) =>
        message.id === messageId
          ? { ...message, recommendedState: "loading", recommendedOpen: true }
          : message
      ),
      recommendReveal: { id: messageId }
    }));
    try {
      const result = await generateRecommendedQuestions(messageId);
      if (result.status === "FAILED") {
        throw new Error("推荐问题生成失败");
      }
      const list = result.status === "SUCCESS" ? result.questions : [];
      set((state) => ({
        messages: state.messages.map((message) =>
          message.id === messageId
            ? { ...message, recommended: list, recommendedState: "ready", recommendedOpen: true }
            : message
        ),
        // 就绪后内容变高，再次请求滚入视口，确保问题不被输入框遮挡
        recommendReveal: { id: messageId }
      }));
    } catch {
      // 失败置 error 态，由面板内「重试」按钮兜底
      set((state) => ({
        messages: state.messages.map((message) =>
          message.id === messageId
            ? { ...message, recommendedState: "error", recommendedOpen: true }
            : message
        ),
        recommendReveal: { id: messageId }
      }));
    }
  },
  toggleRecommended: (messageId) => {
    const target = get().messages.find((message) => message.id === messageId);
    if (!target) return;
    // 加载中：保持展开，就绪后原地显示（再次点击不折叠，避免打断）
    if (target.recommendedState === "loading") {
      set((state) => ({
        messages: state.messages.map((message) =>
          message.id === messageId ? { ...message, recommendedOpen: true } : message
        ),
        recommendReveal: { id: messageId }
      }));
      return;
    }
    // 已尝试过（成功或失败）：纯展开/收起切换 失败态的重试走面板内按钮
    if (target.recommendedState === "ready" || target.recommendedState === "error") {
      const willOpen = !target.recommendedOpen;
      set((state) => ({
        messages: state.messages.map((message) =>
          message.id === messageId
            ? { ...message, recommendedOpen: !message.recommendedOpen }
            : message
        ),
        // 仅在“展开”时滚入视口；收起不滚（保持既有引用避免误触发）
        recommendReveal: willOpen ? { id: messageId } : state.recommendReveal
      }));
      return;
    }
    // idle：手动发起加载 必展开
    void get().loadRecommended(messageId);
  }
}));
