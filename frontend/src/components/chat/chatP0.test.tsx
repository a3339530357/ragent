import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";

import { ChatInput } from "@/components/chat/ChatInput";
import { MessageItem } from "@/components/chat/MessageItem";
import { ThinkingIndicator } from "@/components/chat/ThinkingIndicator";
import { createStreamResponse } from "@/hooks/useStreamResponse";
import { stopTask } from "@/services/chatService";
import { describeStreamError, useChatStore } from "@/stores/chatStore";
import { useLayoutStore } from "@/stores/layoutStore";
import type { Message } from "@/types";

vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
vi.mock("@/services/chatService", () => ({
  stopTask: vi.fn(() => Promise.resolve()),
  submitFeedback: vi.fn(),
  cancelFeedback: vi.fn(),
  generateRecommendedQuestions: vi.fn()
}));

const initialState = useChatStore.getState();
const encoder = new TextEncoder();

/**
 * 构造一个 SSE 响应：每次读取吐出一段 chunk，读完后可选抛出读取错误（模拟中途断网）
 * 注意必须按读取节奏 pull：若在 start 里 enqueue 后立刻 error，规范会丢弃尚未读走的数据
 */
function sseResponse(chunks: string[], failWith?: Error) {
  let index = 0;
  const body = new ReadableStream<Uint8Array>({
    pull(controller) {
      if (index < chunks.length) {
        controller.enqueue(encoder.encode(chunks[index++]));
        return;
      }
      if (failWith) {
        controller.error(failWith);
      } else {
        controller.close();
      }
    }
  });
  return new Response(body, { status: 200, headers: { "Content-Type": "text/event-stream" } });
}

const evt = (name: string, data: unknown) => `event: ${name}\ndata: ${JSON.stringify(data)}\n\n`;

beforeEach(() => {
  useChatStore.setState(initialState, true);
  vi.mocked(stopTask).mockClear();
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("流式请求重试（中途断开不得重发问题）", () => {
  it("已收到事件后断开：不重试，直接抛错", async () => {
    const fetchMock = vi.fn(async () =>
      sseResponse([evt("message", { type: "response", delta: "半截" })], new TypeError("network error"))
    );
    vi.stubGlobal("fetch", fetchMock);
    const onMessage = vi.fn();
    const { start } = createStreamResponse(
      { url: "/x", retryCount: 2, retryDelayMs: 1 },
      { onMessage }
    );
    await expect(start()).rejects.toThrow("network error");
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(onMessage).toHaveBeenCalledTimes(1);
  });

  it("连接阶段就失败：仍按原逻辑重试", async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValueOnce(sseResponse([evt("done", {})]));
    vi.stubGlobal("fetch", fetchMock);
    const onDone = vi.fn();
    const { start } = createStreamResponse({ url: "/x", retryCount: 1, retryDelayMs: 1 }, { onDone });
    await start();
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(onDone).toHaveBeenCalledTimes(1);
  });
});

describe("失败原因翻译", () => {
  it.each([
    [new Error("SSE 请求失败（401）"), "登录已失效，请重新登录后重试"],
    [new Error("SSE 请求失败（429）"), "请求太频繁，请稍后重试"],
    [new Error("SSE 请求失败（502）"), "服务暂时出错（502），请稍后重试"],
    [new TypeError("Failed to fetch"), "网络连接中断，请检查网络后重试"],
    [new Error("模型服务不可用"), "模型服务不可用"]
  ])("%s → %s", (error, expected) => {
    expect(describeStreamError(error)).toBe(expected);
  });
});

describe("发送 → 停止 / 出错（走完整的 sendMessage 流程）", () => {
  it("后端确认停止：正文不再拼接「（已停止生成）」，状态为 cancelled", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        sseResponse([
          evt("meta", { conversationId: "c1", taskId: "t1" }),
          evt("message", { type: "response", delta: "你好" }),
          evt("cancel", { messageId: "m9" }),
          evt("done", {})
        ])
      )
    );
    await useChatStore.getState().sendMessage("问题");
    const last = useChatStore.getState().messages.at(-1)!;
    expect(last.content).toBe("你好");
    expect(last.status).toBe("cancelled");
    expect(useChatStore.getState().isStreaming).toBe(false);
  });

  it("中途断网：保留已生成内容，记录可读的失败原因，且只请求一次", async () => {
    const fetchMock = vi.fn(async () =>
      sseResponse(
        [evt("meta", { conversationId: "c1", taskId: "t1" }), evt("message", { type: "response", delta: "前半段" })],
        new TypeError("network error")
      )
    );
    vi.stubGlobal("fetch", fetchMock);
    await useChatStore.getState().sendMessage("问题");
    const last = useChatStore.getState().messages.at(-1)!;
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(last.status).toBe("error");
    expect(last.content).toBe("前半段");
    expect(last.errorMessage).toBe("网络连接中断，请检查网络后重试");
    expect(useChatStore.getState().isStreaming).toBe(false);
  });
});

describe("停止的即时反馈与兜底", () => {
  const streamingState = (abort: () => void) => ({
    isStreaming: true,
    streamingMessageId: "assistant-1",
    streamTaskId: "t1",
    streamAbort: abort,
    cancelRequested: false,
    messages: [
      { id: "u1", role: "user", content: "问题", status: "done" },
      { id: "assistant-1", role: "assistant", content: "部分", status: "streaming" }
    ] as Message[]
  });

  it("重复点击只发一次停止请求", () => {
    useChatStore.setState(streamingState(vi.fn()));
    useChatStore.getState().cancelGeneration();
    useChatStore.getState().cancelGeneration();
    expect(stopTask).toHaveBeenCalledTimes(1);
    expect(useChatStore.getState().cancelRequested).toBe(true);
  });

  it("后端一直不确认：6 秒后本地断开并按已停止收尾", () => {
    vi.useFakeTimers();
    const abort = vi.fn();
    useChatStore.setState(streamingState(abort));
    useChatStore.getState().cancelGeneration();
    vi.advanceTimersByTime(5999);
    expect(useChatStore.getState().isStreaming).toBe(true);
    vi.advanceTimersByTime(1);
    const state = useChatStore.getState();
    expect(abort).toHaveBeenCalledTimes(1);
    expect(state.isStreaming).toBe(false);
    expect(state.cancelRequested).toBe(false);
    expect(state.messages.at(-1)?.status).toBe("cancelled");
    expect(state.messages.at(-1)?.content).toBe("部分");
  });

  it("后端已正常确认：兜底计时器不再动手", () => {
    vi.useFakeTimers();
    const abort = vi.fn();
    useChatStore.setState(streamingState(abort));
    useChatStore.getState().cancelGeneration();
    useChatStore.setState({ isStreaming: false, streamingMessageId: null, cancelRequested: false });
    vi.advanceTimersByTime(7000);
    expect(abort).not.toHaveBeenCalled();
  });

  it("输入区在停止中显示「停止中」且不可再点", () => {
    useChatStore.setState({ ...streamingState(vi.fn()), cancelRequested: true });
    render(<ChatInput />);
    const btn = screen.getByRole("button", { name: "正在停止" }) as HTMLButtonElement;
    expect(btn.disabled).toBe(true);
    expect(btn.textContent).toContain("停止中");
  });
});

describe("出错重试", () => {
  const failed: Message[] = [
    { id: "u1", role: "user", content: "公司年假怎么算", status: "done" },
    {
      id: "assistant-9",
      role: "assistant",
      content: "",
      status: "error",
      errorMessage: "网络连接中断，请检查网络后重试"
    }
  ];

  it("retryMessage 撤掉失败的一轮并用同一问题重发", async () => {
    const send = vi.fn(async () => undefined);
    useChatStore.setState({ messages: failed, sendMessage: send });
    await useChatStore.getState().retryMessage("assistant-9");
    expect(useChatStore.getState().messages).toHaveLength(0);
    expect(send).toHaveBeenCalledWith("公司年假怎么算");
  });

  it("失败的回答显示原因和重试按钮，点击触发重试", () => {
    const retry = vi.fn(async () => undefined);
    useChatStore.setState({ messages: failed, retryMessage: retry });
    render(<MessageItem message={failed[1]} />);
    expect(screen.getByRole("alert").textContent).toContain("网络连接中断，请检查网络后重试");
    fireEvent.click(screen.getByRole("button", { name: "重试" }));
    expect(retry).toHaveBeenCalledWith("assistant-9");
  });

  it("不是最后一条的失败回答不给重试", () => {
    useChatStore.setState({
      messages: [...failed, { id: "u2", role: "user", content: "再问", status: "done" }]
    });
    render(<MessageItem message={failed[1]} />);
    expect(screen.queryByRole("button", { name: "重试" })).toBeNull();
  });

  it("已停止的回答显示「已停止生成」标签", () => {
    const stopped: Message = { id: "m1", role: "assistant", content: "部分回答", status: "cancelled" };
    useChatStore.setState({ messages: [stopped] });
    render(<MessageItem message={stopped} />);
    expect(screen.getByText("已停止生成")).toBeTruthy();
  });
});

describe("思考区收放", () => {
  it("思考中展开；结束后自动收起成一行并显示用时，可再点开", () => {
    const { rerender } = render(<ThinkingIndicator content="先查制度" active />);
    const header = () => screen.getByRole("button", { expanded: undefined }) as HTMLButtonElement;
    expect(header().getAttribute("aria-expanded")).toBe("true");
    expect(header().textContent).toContain("正在思考");

    rerender(<ThinkingIndicator content="先查制度" active={false} duration={12} />);
    expect(header().getAttribute("aria-expanded")).toBe("false");
    expect(header().textContent).toContain("已思考 12 秒");

    act(() => {
      fireEvent.click(header());
    });
    expect(header().getAttribute("aria-expanded")).toBe("true");
  });
});

describe("宽屏模式", () => {
  it("切换后写入本地存储，刷新后仍保持", () => {
    window.localStorage.removeItem("ragent_chat_wide");
    useLayoutStore.setState({ wideMode: false });
    useLayoutStore.getState().toggleWideMode();
    expect(useLayoutStore.getState().wideMode).toBe(true);
    expect(window.localStorage.getItem("ragent_chat_wide")).toBe("1");
    useLayoutStore.getState().toggleWideMode();
    expect(useLayoutStore.getState().wideMode).toBe(false);
    expect(window.localStorage.getItem("ragent_chat_wide")).toBe("0");
  });
});
