import * as React from "react";
import { Virtuoso, type VirtuosoHandle } from "react-virtuoso";
import { ChevronDown } from "lucide-react";

import { AgentTurnItem } from "@/components/agent/AgentTurn";
import { AgentWelcomeScreen } from "@/components/agent/AgentWelcomeScreen";
import { cn } from "@/lib/utils";
import type { AgentMessage, AgentTurn } from "@/types/agent";

// 距底部小于该值视为「贴底」，流式输出只对贴底用户自动跟随
const NEAR_BOTTOM_PX = 80;

interface AgentMessageListProps {
  messages: AgentMessage[];
  isLoading: boolean;
  isStreaming: boolean;
  sessionKey?: string | null;
}

// 用户消息开启新一轮 其后的助手消息全部配对入同一张 Turn 卡
// 一问对多答只出在确认续跑上：挂起那条与续答同属一次提问 拆两张卡会读成问了两次
function groupTurns(messages: AgentMessage[]): AgentTurn[] {
  const turns: AgentTurn[] = [];
  for (const message of messages) {
    if (message.role === "user") {
      turns.push({ id: message.id, index: turns.length + 1, user: message, assistants: [] });
    } else {
      const last = turns[turns.length - 1];
      if (last) {
        last.assistants.push(message);
      } else {
        turns.push({ id: message.id, index: turns.length + 1, assistants: [message] });
      }
    }
  }
  return turns;
}

export function AgentMessageList({
  messages,
  isLoading,
  isStreaming,
  sessionKey
}: AgentMessageListProps) {
  const virtuosoRef = React.useRef<VirtuosoHandle | null>(null);
  const scrollerRef = React.useRef<HTMLElement | null>(null);
  const lastSessionRef = React.useRef<string | null>(null);
  const pendingScrollRef = React.useRef(true);
  const settleTimerRef = React.useRef<number | null>(null);
  const heightScrollRafRef = React.useRef<number | null>(null);
  const prevStreamingRef = React.useRef(false);
  // 贴底跟随：用户往上翻历史时流式输出不再强制拖回底部
  const autoFollowRef = React.useRef(true);
  const [showJumpButton, setShowJumpButton] = React.useState(false);
  const handleScrollTrack = React.useCallback(() => {
    const scroller = scrollerRef.current;
    if (!scroller) return;
    const nearBottom = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight < NEAR_BOTTOM_PX;
    autoFollowRef.current = nearBottom;
    // 布尔未翻转时不触发重渲染，滚动高频事件下保证零额外渲染
    setShowJumpButton((prev) => (prev === !nearBottom ? prev : !nearBottom));
  }, []);
  const jumpToBottom = React.useCallback(() => {
    autoFollowRef.current = true;
    setShowJumpButton(false);
    const virtuoso = virtuosoRef.current;
    if (virtuoso) {
      virtuoso.scrollToIndex({ index: "LAST", align: "end", behavior: "smooth" });
      return;
    }
    const scroller = scrollerRef.current;
    if (scroller) {
      scroller.scrollTo({ top: scroller.scrollHeight, behavior: "smooth" });
    }
  }, []);
  const initialTopMostItemIndex = React.useMemo(
    () => ({ index: "LAST" as const, align: "end" as const }),
    []
  );

  const turns = React.useMemo(() => groupTurns(messages), [messages]);

  const scrollToBottom = React.useCallback(() => {
    virtuosoRef.current?.scrollToIndex({ index: "LAST", align: "end", behavior: "auto" });
    const scroller = scrollerRef.current;
    if (scroller) {
      scroller.scrollTop = scroller.scrollHeight;
    }
  }, []);

  const stickToBottom = React.useCallback(() => {
    const scroller = scrollerRef.current;
    if (!scroller) return;
    scroller.scrollTop = scroller.scrollHeight;
  }, []);

  React.useEffect(() => {
    const nextKey = sessionKey ?? "empty";
    if (lastSessionRef.current !== nextKey) {
      lastSessionRef.current = nextKey;
      pendingScrollRef.current = true;
      if (settleTimerRef.current) {
        window.clearTimeout(settleTimerRef.current);
        settleTimerRef.current = null;
      }
    }
  }, [sessionKey]);

  React.useEffect(() => {
    const wasStreaming = prevStreamingRef.current;
    prevStreamingRef.current = isStreaming;
    if (!wasStreaming && isStreaming) {
      // 新一轮回答开始：无条件回底（用户刚发完消息，理应看到回答）
      autoFollowRef.current = true;
      setShowJumpButton(false);
      stickToBottom();
      const timer = window.setTimeout(stickToBottom, 120);
      return () => window.clearTimeout(timer);
    }
    if (wasStreaming && !isStreaming) {
      // 流结束的收尾贴底只在用户仍贴底时做，翻历史中被拽走会打断阅读
      if (autoFollowRef.current) {
        scrollToBottom();
        const timer = window.setTimeout(scrollToBottom, 120);
        const lateTimer = window.setTimeout(scrollToBottom, 360);
        return () => {
          window.clearTimeout(timer);
          window.clearTimeout(lateTimer);
        };
      }
    }
    return;
  }, [isStreaming, stickToBottom, scrollToBottom]);

  React.useLayoutEffect(() => {
    if (!pendingScrollRef.current || isStreaming || isLoading || messages.length === 0) {
      return;
    }
    let attempts = 0;
    let rafId = 0;
    let active = true;
    const run = () => {
      scrollToBottom();
      attempts += 1;
      if (attempts < 3) {
        rafId = window.requestAnimationFrame(run);
      }
    };
    run();
    const timer = window.setTimeout(scrollToBottom, 240);
    const lateTimer = window.setTimeout(scrollToBottom, 900);
    const handleLoad = () => {
      if (active) {
        scrollToBottom();
      }
    };
    if (document.readyState === "complete") {
      handleLoad();
    } else {
      window.addEventListener("load", handleLoad, { once: true });
    }
    if (document.fonts?.ready) {
      document.fonts.ready.then(() => {
        if (active) {
          scrollToBottom();
        }
      });
    }
    if (settleTimerRef.current) {
      window.clearTimeout(settleTimerRef.current);
    }
    settleTimerRef.current = window.setTimeout(() => {
      pendingScrollRef.current = false;
      settleTimerRef.current = null;
    }, 1500);
    return () => {
      active = false;
      window.cancelAnimationFrame(rafId);
      window.clearTimeout(timer);
      window.clearTimeout(lateTimer);
      if (settleTimerRef.current) {
        window.clearTimeout(settleTimerRef.current);
        settleTimerRef.current = null;
      }
      window.removeEventListener("load", handleLoad);
    };
  }, [messages.length, isStreaming, isLoading, sessionKey, scrollToBottom]);

  React.useEffect(() => {
    return () => {
      if (heightScrollRafRef.current) {
        window.cancelAnimationFrame(heightScrollRafRef.current);
        heightScrollRafRef.current = null;
      }
      if (settleTimerRef.current) {
        window.clearTimeout(settleTimerRef.current);
        settleTimerRef.current = null;
      }
    };
  }, []);

  const handleTotalListHeightChanged = React.useCallback(() => {
    if (isLoading) {
      return;
    }
    // 流式中只对贴底用户跟随；切会话的落底（pendingScroll）不受贴底状态影响
    const shouldStick = pendingScrollRef.current || (isStreaming && autoFollowRef.current);
    if (!shouldStick) return;
    if (heightScrollRafRef.current) {
      return;
    }
    heightScrollRafRef.current = window.requestAnimationFrame(() => {
      heightScrollRafRef.current = null;
      if (isStreaming) {
        stickToBottom();
      } else {
        scrollToBottom();
      }
    });
  }, [isStreaming, isLoading, scrollToBottom, stickToBottom]);

  // 三连击选段拦截：阻止浏览器把选区扩散到相邻消息 手动只选中被点击的块级元素
  const handleTripleClickDown = React.useCallback((e: React.MouseEvent<HTMLDivElement>) => {
    if (e.detail < 3) return;
    e.preventDefault();
    const target = e.target as HTMLElement;
    const block = target.closest("p, li, h1, h2, h3, h4, h5, h6, pre, blockquote, td, th");
    const container = block && e.currentTarget.contains(block) ? block : e.currentTarget;
    const sel = window.getSelection();
    if (sel) {
      const range = document.createRange();
      range.selectNodeContents(container);
      sel.removeAllRanges();
      sel.addRange(range);
    }
  }, []);

  const List = React.useMemo(() => {
    const Comp = React.forwardRef<HTMLDivElement, React.HTMLAttributes<HTMLDivElement>>(
      ({ className, ...props }, ref) => (
        <div ref={ref} className={cn("agent-stream-rows", className)} {...props} />
      )
    );
    Comp.displayName = "AgentMessageList";
    return Comp;
  }, []);

  // 顶部留白放 Header 写在滚动容器或 List 的 padding 都会坏 Virtuoso 的账
  const Header = React.useMemo(() => {
    const Comp = () => <div aria-hidden="true" className="h-6" />;
    Comp.displayName = "AgentMessageListHeader";
    return Comp;
  }, []);

  const Footer = React.useMemo(() => {
    const Comp = () => <div aria-hidden="true" className="h-6" />;
    Comp.displayName = "AgentMessageListFooter";
    return Comp;
  }, []);

  if (messages.length === 0) {
    if (isLoading) {
      return <div className="agent-stream h-full" />;
    }
    return (
      <div className="agent-stream h-full">
        <AgentWelcomeScreen />
      </div>
    );
  }

  return (
    <div className="relative h-full">
      <Virtuoso
      key={sessionKey ?? "empty"}
      ref={virtuosoRef}
      data={turns}
      initialTopMostItemIndex={initialTopMostItemIndex}
      // 贴底逻辑与工作流版一致：不启用 followOutput 由 streaming effect 与高度变化回调接管
      followOutput={false}
      scrollerRef={(node) => {
        const prev = scrollerRef.current;
        if (prev) {
          prev.removeEventListener("scroll", handleScrollTrack);
        }
        scrollerRef.current = node as HTMLElement | null;
        if (node) {
          node.addEventListener("scroll", handleScrollTrack, { passive: true });
        }
      }}
      totalListHeightChanged={handleTotalListHeightChanged}
      className="agent-stream h-full"
      components={{ Header, List, Footer }}
      itemContent={(_index, turn) => (
        <div
          data-turn-id={turn.id}
          // 轮距 16px 用 padding 承载 避免 margin 影响 Virtuoso 高度测量
          className="pb-4"
          onMouseDown={handleTripleClickDown}
        >
          <AgentTurnItem turn={turn} />
        </div>
      )}
      />
      {showJumpButton ? (
        <button
          type="button"
          aria-label="回到底部"
          title="回到底部"
          onClick={jumpToBottom}
          className="absolute bottom-4 right-4 z-10 inline-flex h-9 w-9 items-center justify-center rounded-full border bg-background/90 text-muted-foreground shadow-md backdrop-blur transition-colors hover:text-foreground"
        >
          <ChevronDown className="h-5 w-5" />
        </button>
      ) : null}
    </div>
  );
}
