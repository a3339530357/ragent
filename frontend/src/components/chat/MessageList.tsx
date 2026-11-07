import * as React from "react";
import { ArrowDown } from "lucide-react";
import { Virtuoso, type VirtuosoHandle } from "react-virtuoso";

import { MessageItem } from "@/components/chat/MessageItem";
import { QuestionRail, type QuestionRailItem } from "@/components/chat/QuestionRail";
import { WelcomeScreen } from "@/components/chat/WelcomeScreen";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import { useLayoutStore } from "@/stores/layoutStore";
import type { Message } from "@/types";

interface MessageListProps {
  messages: Message[];
  isLoading: boolean;
  isStreaming: boolean;
  sessionKey?: string | null;
}

export function MessageList({ messages, isLoading, isStreaming, sessionKey }: MessageListProps) {
  const virtuosoRef = React.useRef<VirtuosoHandle | null>(null);
  const scrollerRef = React.useRef<HTMLElement | null>(null);
  const lastSessionRef = React.useRef<string | null>(null);
  const pendingScrollRef = React.useRef(true);
  const settleTimerRef = React.useRef<number | null>(null);
  const heightScrollRafRef = React.useRef<number | null>(null);
  const prevStreamingRef = React.useRef(false);
  const recommendReveal = useChatStore((state) => state.recommendReveal);
  const wideMode = useLayoutStore((state) => state.wideMode);
  // 当前可视区第一条的下标：切换宽屏后内容重排，用它把用户正在看的那条留在原处
  const visibleStartRef = React.useRef(0);
  const initialTopMostItemIndex = React.useMemo(
    () => ({ index: "LAST" as const, align: "end" as const }),
    []
  );
  const [visibleEnd, setVisibleEnd] = React.useState(0);
  // 生成期间是否跟随新内容贴底：用户主动往上翻即关闭，翻回底部/点「回到底部」/发新问题时恢复
  // 用 ref 而非 state：贴底判断发生在高度变化回调里，必须读到同步最新值
  const followRef = React.useRef(true);
  // 用户是否离开了底部（驱动「回到底部」按钮）；hasNew 表示离开期间又有新内容
  const [detached, setDetached] = React.useState(false);
  const [hasNew, setHasNew] = React.useState(false);
  // 只有用户自己的操作（滚轮/触摸/键盘/拖滚动条或拖选）引起的滚动才用来判断「离开底部」，
  // 程序贴底、内容收缩导致的 scrollTop 变化不算
  const lastUserInputRef = React.useRef(0);
  const pointerDownRef = React.useRef(false);

  const userQuestions = React.useMemo<QuestionRailItem[]>(() => {
    const items: QuestionRailItem[] = [];
    messages.forEach((msg, flatIndex) => {
      if (msg.role !== "user") return;
      const text = msg.content.replace(/\s+/g, " ").trim();
      if (!text) return;
      items.push({ id: msg.id, flatIndex, text });
    });
    return items;
  }, [messages]);

  const activeQuestionId = React.useMemo(() => {
    if (userQuestions.length === 0) return null;
    let last: string | null = userQuestions[0].id;
    for (const q of userQuestions) {
      if (q.flatIndex <= visibleEnd) {
        last = q.id;
      } else {
        break;
      }
    }
    return last;
  }, [userQuestions, visibleEnd]);

  const handleSelectQuestion = React.useCallback((flatIndex: number) => {
    // 主动跳到历史问题：停止跟随，否则生成中的新内容会把视图拽回底部
    followRef.current = false;
    setDetached(true);
    virtuosoRef.current?.scrollToIndex({
      index: flatIndex,
      align: "start",
      behavior: "smooth"
    });
  }, []);

  const handleRangeChanged = React.useCallback(
    (range: { startIndex: number; endIndex: number }) => {
      visibleStartRef.current = range.startIndex;
      setVisibleEnd(range.endIndex);
    },
    []
  );

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
      followRef.current = true;
      setDetached(false);
      setHasNew(false);
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
      // 用户刚发出新问题：无论之前翻到哪，都回到底部看这一轮
      followRef.current = true;
      setDetached(false);
      setHasNew(false);
      stickToBottom();
      const timer = window.setTimeout(stickToBottom, 120);
      return () => window.clearTimeout(timer);
    }
    if (wasStreaming && !isStreaming) {
      if (!followRef.current) {
        return;
      }
      scrollToBottom();
      const timer = window.setTimeout(scrollToBottom, 120);
      const lateTimer = window.setTimeout(scrollToBottom, 360);
      return () => {
        window.clearTimeout(timer);
        window.clearTimeout(lateTimer);
      };
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

  // 展开推荐面板后把该条滚入视口：直接量真实 DOM 几何 不依赖 Virtuoso 的尺寸缓存
  // 缓存滞后会按面板展开前的旧高度欠滚 使变高的面板落到折叠线下 被输入框遮挡
  // 面板从骨架→问题会再次变高 故 ready 时会重新触发本效果按最终高度对齐 并在淡入动画结束后补一次精确贴齐
  React.useEffect(() => {
    if (!recommendReveal) return;
    const revealId = recommendReveal.id;
    const revealBottom = (behavior: ScrollBehavior) => {
      const scroller = scrollerRef.current;
      if (!scroller) return;
      const el = scroller.querySelector<HTMLElement>(`[data-message-id="${CSS.escape(revealId)}"]`);
      if (!el) return;
      const gap = 12; // 面板底部与输入框留出的呼吸间距
      const delta = el.getBoundingClientRect().bottom - (scroller.getBoundingClientRect().bottom - gap);
      // 仅在被遮挡时下滚露出 已完整可见则不上滚 避免打断阅读
      if (delta > 0) {
        scroller.scrollTo({ top: scroller.scrollTop + delta, behavior });
      }
    };
    const smoothTimer = window.setTimeout(() => revealBottom("smooth"), 100);
    const snapTimer = window.setTimeout(() => revealBottom("auto"), 420);
    return () => {
      window.clearTimeout(smoothTimer);
      window.clearTimeout(snapTimer);
    };
  }, [recommendReveal]);

  const handleTotalListHeightChanged = React.useCallback(() => {
    if (isLoading) {
      return;
    }
    if (isStreaming && !followRef.current) {
      setHasNew(true);
    }
    const shouldStick = (isStreaming && followRef.current) || pendingScrollRef.current;
    if (!shouldStick) return;
    if (heightScrollRafRef.current) {
      return;
    }
    heightScrollRafRef.current = window.requestAnimationFrame(() => {
      heightScrollRafRef.current = null;
      // 排队期间用户可能已经往上翻，执行前再确认一次
      if (isStreaming && !followRef.current && !pendingScrollRef.current) return;
      if (isStreaming) {
        stickToBottom();
      } else {
        scrollToBottom();
      }
    });
  }, [isStreaming, isLoading, scrollToBottom, stickToBottom]);

  const markUserInput = React.useCallback(() => {
    lastUserInputRef.current = performance.now();
  }, []);

  // 往上的操作立即关闭跟随（同步生效，抢在下一次贴底之前）；是否真的离开底部交给滚动事件按距离判定
  const handleWheel = React.useCallback(
    (event: React.WheelEvent<HTMLDivElement>) => {
      markUserInput();
      if (event.deltaY < 0) followRef.current = false;
    },
    [markUserInput]
  );

  const touchYRef = React.useRef<number | null>(null);
  const handleTouchStart = React.useCallback(
    (event: React.TouchEvent<HTMLDivElement>) => {
      markUserInput();
      touchYRef.current = event.touches[0]?.clientY ?? null;
    },
    [markUserInput]
  );
  const handleTouchMove = React.useCallback(
    (event: React.TouchEvent<HTMLDivElement>) => {
      markUserInput();
      const y = event.touches[0]?.clientY;
      // 手指往下拖 = 内容往上翻
      if (y != null && touchYRef.current != null && y > touchYRef.current) {
        followRef.current = false;
      }
      touchYRef.current = y ?? null;
    },
    [markUserInput]
  );

  const handleKeyDown = React.useCallback(
    (event: React.KeyboardEvent<HTMLDivElement>) => {
      const upKeys = ["ArrowUp", "PageUp", "Home"];
      const downKeys = ["ArrowDown", "PageDown", "End", " "];
      if (upKeys.includes(event.key) || (event.key === " " && event.shiftKey)) {
        markUserInput();
        followRef.current = false;
      } else if (downKeys.includes(event.key)) {
        markUserInput();
      }
    },
    [markUserInput]
  );

  const handlePointerDown = React.useCallback(() => {
    pointerDownRef.current = true;
    markUserInput();
    const release = () => {
      pointerDownRef.current = false;
      window.removeEventListener("pointerup", release);
      window.removeEventListener("pointercancel", release);
    };
    window.addEventListener("pointerup", release);
    window.addEventListener("pointercancel", release);
  }, [markUserInput]);

  // scroll 不冒泡，但捕获阶段会经过祖先节点，故在外层用 onScrollCapture 监听虚拟列表的滚动容器
  const handleScrollCapture = React.useCallback((event: React.UIEvent<HTMLDivElement>) => {
    const scroller = scrollerRef.current;
    if (!scroller || event.target !== scroller) return;
    const byUser =
      pointerDownRef.current || performance.now() - lastUserInputRef.current < 300;
    if (!byUser) return;
    const distance = scroller.scrollHeight - scroller.scrollTop - scroller.clientHeight;
    const away = distance > 48;
    followRef.current = !away;
    setDetached(away);
    if (!away) setHasNew(false);
  }, []);

  const jumpToBottom = React.useCallback(() => {
    followRef.current = true;
    setDetached(false);
    setHasNew(false);
    const scroller = scrollerRef.current;
    if (!scroller) return;
    const reduceMotion = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    scroller.scrollTo({ top: scroller.scrollHeight, behavior: reduceMotion ? "auto" : "smooth" });
    // 虚拟列表对未渲染条目的高度是估算的，平滑滚动结束后按真实高度补一次精确贴底
    window.setTimeout(() => {
      if (followRef.current) scrollToBottom();
    }, 450);
  }, [scrollToBottom]);

  // Intercept triple-click at mousedown phase to prevent browser from
  // extending paragraph selection across sibling message boundaries.
  // preventDefault() stops the default selection, then we manually select
  // only the clicked block-level element's contents.
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
      ({ className, ...props }, ref) => {
        // 在列表组件内部订阅宽屏状态：组件类型保持稳定，Virtuoso 不会因切换而整体重挂载
        const wide = useLayoutStore((state) => state.wideMode);
        return (
          <div
            ref={ref}
            className={cn(
              "mx-auto space-y-10 px-6 pt-10 pb-2 md:px-8",
              // 宽屏时右侧多留空间，避免内容压到右侧的问题导航条
              wide ? "max-w-[1280px] lg:pr-14" : "max-w-[840px]",
              className
            )}
            {...props}
          />
        );
      }
    );
    Comp.displayName = "MessageList";
    return Comp;
  }, []);

  // 切换宽屏：行宽变化导致每条高度重算，原来的 scrollTop 会指向别的内容
  // 在底部的继续贴底；翻在中间的把切换前看到的第一条重新对齐到顶部
  const prevWideRef = React.useRef(wideMode);
  React.useEffect(() => {
    if (prevWideRef.current === wideMode) return;
    prevWideRef.current = wideMode;
    const atBottom = followRef.current && !detached;
    const anchor = visibleStartRef.current;
    let raf2 = 0;
    const raf1 = window.requestAnimationFrame(() => {
      raf2 = window.requestAnimationFrame(() => {
        if (atBottom) {
          scrollToBottom();
        } else {
          virtuosoRef.current?.scrollToIndex({ index: anchor, align: "start", behavior: "auto" });
        }
      });
    });
    return () => {
      window.cancelAnimationFrame(raf1);
      window.cancelAnimationFrame(raf2);
    };
  }, [wideMode, detached, scrollToBottom]);

  const Footer = React.useMemo(() => {
    const Comp = () => <div aria-hidden="true" className="h-8" />;
    Comp.displayName = "MessageListFooter";
    return Comp;
  }, []);

  if (messages.length === 0) {
    if (isLoading) {
      return <div className="h-full" />;
    }
    return <WelcomeScreen />;
  }

  return (
    <div
      className="relative h-full"
      onWheel={handleWheel}
      onTouchStart={handleTouchStart}
      onTouchMove={handleTouchMove}
      onKeyDown={handleKeyDown}
      onPointerDown={handlePointerDown}
      onScrollCapture={handleScrollCapture}
    >
      <Virtuoso
        key={sessionKey ?? "empty"}
        ref={virtuosoRef}
        data={messages}
        initialTopMostItemIndex={initialTopMostItemIndex}
        // 不启用 followOutput 自动贴底：发送/流式贴底由 streaming effect 与 totalListHeightChanged 负责，
        // 会话加载贴底由布局 effect 负责。若开启，展开推荐/来源等“已到底后条目变高”会被当作新内容触发贴底，
        // 把变高的最后一条（回答+面板）顶得超出视口、回答被推到可视区上方
        followOutput={false}
        scrollerRef={(node) => {
          scrollerRef.current = node as HTMLElement | null;
        }}
        totalListHeightChanged={handleTotalListHeightChanged}
        rangeChanged={handleRangeChanged}
        className="h-full"
        components={{ List, Footer }}
        itemContent={(index, message) => (
          <div
            data-message-id={message.id}
            className={cn(index === messages.length - 1 && "animate-fade-up")}
            onMouseDown={handleTripleClickDown}
          >
            <MessageItem message={message} />
          </div>
        )}
      />
      {detached ? (
        <button
          type="button"
          onClick={jumpToBottom}
          className={cn(
            "absolute bottom-3 left-1/2 z-20 inline-flex -translate-x-1/2 items-center gap-1.5 rounded-full border bg-white px-3 py-1.5 text-xs shadow-[0_4px_14px_rgba(0,0,0,0.08)] transition-colors",
            "animate-pop-in motion-reduce:animate-none active:scale-95",
            hasNew
              ? "border-[#BFDBFE] text-[#2563EB] hover:bg-[#EFF6FF]"
              : "border-[#E5E5E5] text-[#666666] hover:bg-[#F5F5F5]"
          )}
          aria-label={hasNew ? "有新内容，回到底部" : "回到底部"}
        >
          <ArrowDown className="h-3.5 w-3.5" />
          {hasNew ? "有新内容" : "回到底部"}
        </button>
      ) : null}
      <QuestionRail
        items={userQuestions}
        activeId={activeQuestionId}
        onSelect={handleSelectQuestion}
      />
    </div>
  );
}
