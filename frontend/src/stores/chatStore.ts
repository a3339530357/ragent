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
  renameSession as renameSessionRequest,
  rollbackConversationFromMessage,
  type ConversationMessageVO
} from "@/services/sessionService";
import { stopTask, submitFeedback } from "@/services/chatService";
import { buildQuery } from "@/utils/helpers";
import { createStreamResponse } from "@/hooks/useStreamResponse";
import { storage } from "@/utils/storage";

interface ChatState {
  sessions: Session[];
  currentSessionId: string | null;
  messages: Message[];
  messageCache: Record<string, MessageCacheEntry>;
  isLoading: boolean;
  sessionsLoaded: boolean;
  sessionsFetchedAt: number | null;
  inputFocusKey: number;
  isStreaming: boolean;
  isCreatingNew: boolean;
  deepThinkingEnabled: boolean;
  selectedModelId: string | null;
  thinkingStartAt: number | null;
  streamTaskId: string | null;
  streamAbort: (() => void) | null;
  streamingMessageId: string | null;
  cancelRequested: boolean;
  editingMessageId: string | null;
  editingMessageContent: string;
  fetchSessions: (options?: { force?: boolean }) => Promise<void>;
  createSession: () => Promise<string>;
  deleteSession: (sessionId: string) => Promise<void>;
  renameSession: (sessionId: string, title: string) => Promise<void>;
  selectSession: (sessionId: string, options?: { force?: boolean }) => Promise<void>;
  refreshCurrentMessages: () => Promise<void>;
  updateSessionTitle: (sessionId: string, title: string) => void;
  setDeepThinkingEnabled: (enabled: boolean) => void;
  setSelectedModelId: (id: string | null) => void;
  beginEditingMessage: (messageId: string, content: string) => void;
  clearEditingMessage: () => void;
  resendEditedMessage: (content: string) => Promise<boolean>;
  sendMessage: (content: string) => Promise<void>;
  cancelGeneration: () => void;
  appendStreamContent: (delta: string) => void;
  appendThinkingContent: (delta: string) => void;
  submitFeedback: (messageId: string, feedback: FeedbackValue) => Promise<void>;
}

interface MessageCacheEntry {
  messages: Message[];
  updatedAt: number;
}

const SESSION_LIST_CACHE_TTL_MS = 60 * 1000;
const MESSAGE_CACHE_TTL_MS = 5 * 60 * 1000;
const MAX_CACHED_MESSAGE_SESSIONS = 20;

let sessionsRequest: Promise<void> | null = null;
const messageRequests = new Map<string, Promise<void>>();

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

function mapMessages(data: ConversationMessageVO[]): Message[] {
  return data.map((item) => ({
    id: String(item.id),
    role: item.role === "assistant" ? "assistant" : "user",
    content: item.content,
    thinking: item.thinkingContent || undefined,
    thinkingDuration: item.thinkingDuration || undefined,
    isDeepThinking: Boolean(item.thinkingContent),
    createdAt: item.createTime,
    feedback: mapVoteToFeedback(item.vote),
    status: "done",
    citations: item.citations || []
  }));
}

function putMessageCache(
  cache: Record<string, MessageCacheEntry>,
  sessionId: string,
  messages: Message[]
): Record<string, MessageCacheEntry> {
  const next = {
    ...cache,
    [sessionId]: {
      messages,
      updatedAt: Date.now()
    }
  };
  const entries = Object.entries(next).sort(([, left], [, right]) => right.updatedAt - left.updatedAt);
  return Object.fromEntries(entries.slice(0, MAX_CACHED_MESSAGE_SESSIONS));
}

function removeMessageCache(
  cache: Record<string, MessageCacheEntry>,
  sessionId: string
): Record<string, MessageCacheEntry> {
  if (!cache[sessionId]) return cache;
  const next = { ...cache };
  delete next[sessionId];
  return next;
}

function finalizeStreamingMessage(
  messages: Message[],
  streamingMessageId: string | null,
  patch: Partial<Message>
): Message[] {
  return messages.map((message) =>
    message.id === streamingMessageId
      ? {
          ...message,
          ...patch
        }
      : message
  );
}

const API_BASE_URL = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");

export const useChatStore = create<ChatState>((set, get) => ({
  sessions: [],
  currentSessionId: null,
  messages: [],
  messageCache: {},
  isLoading: false,
  sessionsLoaded: false,
  sessionsFetchedAt: null,
  inputFocusKey: 0,
  isStreaming: false,
  isCreatingNew: false,
  deepThinkingEnabled: false,
  selectedModelId: null,
  thinkingStartAt: null,
  streamTaskId: null,
  streamAbort: null,
  streamingMessageId: null,
  cancelRequested: false,
  editingMessageId: null,
  editingMessageContent: "",
  fetchSessions: async (options) => {
    const state = get();
    const now = Date.now();
    const hasFreshCache =
      state.sessionsLoaded &&
      state.sessionsFetchedAt !== null &&
      now - state.sessionsFetchedAt < SESSION_LIST_CACHE_TTL_MS;

    if (!options?.force && hasFreshCache) {
      return;
    }
    if (!options?.force && sessionsRequest) {
      return sessionsRequest;
    }

    const request: Promise<void> = (async () => {
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
        set({ sessions, sessionsFetchedAt: Date.now() });
      } catch (error) {
        toast.error((error as Error).message || "加载会话失败");
      } finally {
        set({ isLoading: false, sessionsLoaded: true });
      }
    })();

    sessionsRequest = request;
    request.finally(() => {
      if (sessionsRequest === request) {
        sessionsRequest = null;
      }
    });
    return request;
  },
  createSession: async () => {
    const state = get();
    if (state.messages.length === 0 && !state.currentSessionId) {
      set({
        isCreatingNew: true,
        isLoading: false,
        thinkingStartAt: null,
        deepThinkingEnabled: false,
        editingMessageId: null,
        editingMessageContent: ""
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
      editingMessageId: null,
      editingMessageContent: ""
    });
    return "";
  },
  deleteSession: async (sessionId) => {
    try {
      await deleteSessionRequest(sessionId);
      set((state) => ({
        sessions: state.sessions.filter((session) => session.id !== sessionId),
        messages: state.currentSessionId === sessionId ? [] : state.messages,
        messageCache: removeMessageCache(state.messageCache, sessionId),
        currentSessionId: state.currentSessionId === sessionId ? null : state.currentSessionId,
        editingMessageId: state.currentSessionId === sessionId ? null : state.editingMessageId,
        editingMessageContent:
          state.currentSessionId === sessionId ? "" : state.editingMessageContent
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
  selectSession: async (sessionId, options) => {
    if (!sessionId) return;
    const state = get();
    if (!options?.force && state.currentSessionId === sessionId && state.messages.length > 0) {
      return;
    }
    const cached = state.messageCache[sessionId];
    const hasFreshCache =
      cached !== undefined && Date.now() - cached.updatedAt < MESSAGE_CACHE_TTL_MS;
    if (!options?.force && hasFreshCache) {
      if (state.isStreaming && state.currentSessionId !== sessionId) {
        get().cancelGeneration();
      }
      set({
        currentSessionId: sessionId,
        messages: cached.messages,
        isLoading: false,
        isCreatingNew: false,
        thinkingStartAt: null,
        isStreaming: false,
        streamTaskId: null,
        streamAbort: null,
        streamingMessageId: null,
        cancelRequested: false,
        editingMessageId: null,
        editingMessageContent: ""
      });
      return;
    }
    if (get().isStreaming && get().currentSessionId !== sessionId) {
      get().cancelGeneration();
    }
    const inFlight = messageRequests.get(sessionId);
    set({
      isLoading: !cached,
      currentSessionId: sessionId,
      messages: cached?.messages ?? [],
      isCreatingNew: false,
      thinkingStartAt: null,
      editingMessageId: null,
      editingMessageContent: ""
    });
    if (inFlight) {
      return inFlight;
    }

    const request = (async () => {
      try {
        const data = await listMessages(sessionId);
        if (get().currentSessionId !== sessionId) {
          return;
        }
        const messages = mapMessages(data);
        set((latest) => ({
          messages,
          messageCache: putMessageCache(latest.messageCache, sessionId, messages)
        }));
      } catch (error) {
        toast.error((error as Error).message || "加载消息失败");
      } finally {
        messageRequests.delete(sessionId);
        if (get().currentSessionId !== sessionId) {
          set({ isLoading: false });
        } else {
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
    })();

    messageRequests.set(sessionId, request);
    return request;
  },
  refreshCurrentMessages: async () => {
    const sessionId = get().currentSessionId;
    if (!sessionId || get().isStreaming) return;
    try {
      const data = await listMessages(sessionId);
      if (get().currentSessionId !== sessionId || get().isStreaming) {
        return;
      }
      const messages = mapMessages(data);
      set((state) => ({
        messages,
        messageCache: putMessageCache(state.messageCache, sessionId, messages)
      }));
    } catch {
      // 消息 ID 同步失败不影响当前对话展示。
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
  setSelectedModelId: (id) => {
    set({ selectedModelId: id });
  },
  beginEditingMessage: (messageId, content) => {
    const state = get();
    if (state.isStreaming || state.isLoading) {
      toast.info("当前对话还在生成中，请稍后再编辑");
      return;
    }
    if (messageId.startsWith("user-")) {
      toast.info("消息正在同步，请稍后再试");
      get()
        .refreshCurrentMessages()
        .catch(() => null);
      return;
    }
    set({
      editingMessageId: messageId,
      editingMessageContent: content,
      inputFocusKey: Date.now()
    });
  },
  clearEditingMessage: () => {
    set({
      editingMessageId: null,
      editingMessageContent: "",
      inputFocusKey: Date.now()
    });
  },
  resendEditedMessage: async (content) => {
    const trimmed = content.trim();
    const state = get();
    if (!trimmed || state.isStreaming || state.isLoading) return false;
    const messageId = state.editingMessageId;
    const conversationId = state.currentSessionId;
    if (!messageId || !conversationId) return false;
    const messageIndex = state.messages.findIndex((message) => message.id === messageId);
    if (messageIndex < 0 || state.messages[messageIndex]?.role !== "user") {
      toast.error("未找到要编辑的历史消息");
      set({ editingMessageId: null, editingMessageContent: "" });
      return false;
    }

    try {
      await rollbackConversationFromMessage(conversationId, messageId);
      if (get().currentSessionId !== conversationId || get().editingMessageId !== messageId) {
        return false;
      }
      set((latest) => {
        const latestIndex = latest.messages.findIndex((message) => message.id === messageId);
        const messages = latestIndex >= 0 ? latest.messages.slice(0, latestIndex) : latest.messages;
        return {
          messages,
          messageCache: putMessageCache(latest.messageCache, conversationId, messages),
          editingMessageId: null,
          editingMessageContent: ""
        };
      });
      await get().sendMessage(trimmed);
      return true;
    } catch (error) {
      toast.error((error as Error).message || "回溯历史消息失败");
      return false;
    }
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
      citations: [],
      createdAt: new Date().toISOString()
    };

    set((state) => {
      const messages = [...state.messages, userMessage, assistantMessage];
      return {
        messages,
        messageCache: state.currentSessionId
          ? putMessageCache(state.messageCache, state.currentSessionId, messages)
          : state.messageCache,
        isStreaming: true,
        streamingMessageId: assistantId,
        thinkingStartAt: null,
        inputFocusKey,
        streamTaskId: null,
        cancelRequested: false
      };
    });

    const conversationId = get().currentSessionId;
    const selectedModelId = get().selectedModelId;
    const query = buildQuery({
      question: trimmed,
      conversationId: conversationId || undefined,
      deepThinking: deepThinkingEnabled ? true : undefined,
      modelId: selectedModelId || undefined
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
          messageCache: putMessageCache(state.messageCache, nextId, state.messages),
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
          set((state) => {
            const target = state.messages.find((message) => message.id === state.streamingMessageId);
            const messages = finalizeStreamingMessage(state.messages, state.streamingMessageId, {
              id: String(payload.messageId),
              status: "done",
              isThinking: false,
              citations: payload.citations || [],
              thinkingDuration:
                target?.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
            });
            return {
              messages,
              messageCache: state.currentSessionId
                ? putMessageCache(state.messageCache, state.currentSessionId, messages)
                : state.messageCache
            };
          });
        } else {
          set((state) => {
            const target = state.messages.find((message) => message.id === state.streamingMessageId);
            const messages = finalizeStreamingMessage(state.messages, state.streamingMessageId, {
              status: "done",
              isThinking: false,
              citations: payload.citations || [],
              thinkingDuration:
                target?.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
            });
            return {
              messages,
              messageCache: state.currentSessionId
                ? putMessageCache(state.messageCache, state.currentSessionId, messages)
                : state.messageCache
            };
          });
        }
      },
      onCancel: (payload: CompletionPayload) => {
        if (get().streamingMessageId !== assistantId) return;
        if (payload?.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
        set((state) => {
          const target = state.messages.find((message) => message.id === state.streamingMessageId);
          const suffix = target?.content.includes("（已停止生成）") ? "" : "\n\n（已停止生成）";
          const messages = finalizeStreamingMessage(state.messages, state.streamingMessageId, {
            id: payload?.messageId ? String(payload.messageId) : target?.id,
            content: `${target?.content ?? ""}${suffix}`,
            status: "cancelled",
            isThinking: false,
            citations: payload?.citations || [],
            thinkingDuration:
              target?.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
          });
          return {
            messages,
            messageCache: state.currentSessionId
              ? putMessageCache(state.messageCache, state.currentSessionId, messages)
              : state.messageCache,
            isStreaming: false,
            thinkingStartAt: null,
            streamTaskId: null,
            streamAbort: null,
            streamingMessageId: null,
            cancelRequested: false
          };
        });
        get()
          .refreshCurrentMessages()
          .catch(() => null);
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
        get()
          .refreshCurrentMessages()
          .catch(() => null);
      },
      onTitle: (payload: { title: string }) => {
        if (get().streamingMessageId !== assistantId) return;
        if (payload?.title && get().currentSessionId) {
          get().updateSessionTitle(get().currentSessionId as string, payload.title);
        }
      },
      onError: (error: Error) => {
        if (get().streamingMessageId !== assistantId) return;
        set((state) => {
          const target = state.messages.find((message) => message.id === state.streamingMessageId);
          const messages = finalizeStreamingMessage(state.messages, state.streamingMessageId, {
            status: "error",
            isThinking: false,
            thinkingDuration:
              target?.thinkingDuration ?? computeThinkingDuration(state.thinkingStartAt)
          });
          return {
            isStreaming: false,
            thinkingStartAt: null,
            streamTaskId: null,
            streamAbort: null,
            cancelRequested: false,
            messages,
            messageCache: state.currentSessionId
              ? putMessageCache(state.messageCache, state.currentSessionId, messages)
              : state.messageCache
          };
        });
        toast.error(error.message || "生成失败");
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
    const { isStreaming, streamTaskId } = get();
    if (!isStreaming) return;
    set({ cancelRequested: true });
    if (streamTaskId) {
      stopTask(streamTaskId).catch(() => null);
    }
  },
  appendStreamContent: (delta) => {
    if (!delta) return;
    set((state) => {
      const shouldFinalizeThinking = state.thinkingStartAt != null;
      const duration = computeThinkingDuration(state.thinkingStartAt);
      const messages = state.messages.map((message) => {
        if (message.id !== state.streamingMessageId) return message;
        if (message.status === "cancelled" || message.status === "error") return message;
        return {
          ...message,
          content: message.content + delta,
          isThinking: shouldFinalizeThinking ? false : message.isThinking,
          thinkingDuration:
            shouldFinalizeThinking && !message.thinkingDuration
              ? duration
              : message.thinkingDuration
        };
      });
      return {
        thinkingStartAt: shouldFinalizeThinking ? null : state.thinkingStartAt,
        messages,
        messageCache: state.currentSessionId
          ? putMessageCache(state.messageCache, state.currentSessionId, messages)
          : state.messageCache
      };
    });
  },
  appendThinkingContent: (delta) => {
    if (!delta) return;
    set((state) => {
      const messages = state.messages.map((message) =>
        message.id === state.streamingMessageId &&
        message.status !== "cancelled" &&
        message.status !== "error"
          ? {
              ...message,
              thinking: `${message.thinking ?? ""}${delta}`,
              isThinking: true
            }
          : message
      );
      return {
        thinkingStartAt: state.thinkingStartAt ?? Date.now(),
        messages,
        messageCache: state.currentSessionId
          ? putMessageCache(state.messageCache, state.currentSessionId, messages)
          : state.messageCache
      };
    });
  },
  submitFeedback: async (messageId, feedback) => {
    const vote = feedback === "like" ? 1 : feedback === "dislike" ? -1 : null;
    const prev = get().messages.find((message) => message.id === messageId)?.feedback ?? null;
    set((state) => {
      const messages = state.messages.map((message) =>
        message.id === messageId ? { ...message, feedback } : message
      );
      return {
        messages,
        messageCache: state.currentSessionId
          ? putMessageCache(state.messageCache, state.currentSessionId, messages)
          : state.messageCache
      };
    });
    if (vote === null) {
      toast.success("取消成功");
      return;
    }
    try {
      await submitFeedback(messageId, vote);
      toast.success(feedback === "like" ? "点赞成功" : "点踩成功");
    } catch (error) {
      set((state) => {
        const messages = state.messages.map((message) =>
          message.id === messageId ? { ...message, feedback: prev } : message
        );
        return {
          messages,
          messageCache: state.currentSessionId
            ? putMessageCache(state.messageCache, state.currentSessionId, messages)
            : state.messageCache
        };
      });
      toast.error((error as Error).message || "反馈保存失败");
    }
  }
}));
