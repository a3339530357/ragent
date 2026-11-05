import * as React from "react";
import { Brain, ChevronDown, Loader2 } from "lucide-react";

import { cn } from "@/lib/utils";

interface ThinkingIndicatorProps {
  /** 思考过程文本（流式追加） */
  content?: string;
  /** 是否仍在思考：为 true 时强制展开并跟随最新内容 */
  active: boolean;
  /** 思考用时（秒），思考结束后才有 */
  duration?: number;
}

/**
 * 深度思考区：同一个容器承载「思考中」与「已思考」两种状态
 * <p>
 * 旧实现在思考结束时把整块大面板换成一行标题，正文会瞬间上跳几百像素。
 * 这里改为：思考中限高滚动（只占几行），结束后用 200ms 高度过渡收起成一行，
 * 让用户看清「它收起了」而不是「它消失了」，之后可随时点开回看
 */
export function ThinkingIndicator({ content, active, duration }: ThinkingIndicatorProps) {
  const [expanded, setExpanded] = React.useState(active);
  const bodyRef = React.useRef<HTMLDivElement | null>(null);
  const followRef = React.useRef(true);
  const wasActiveRef = React.useRef(active);

  // 思考中 → 结束：自动收起；结束态下展开与否交给用户
  React.useEffect(() => {
    if (wasActiveRef.current && !active) {
      setExpanded(false);
    }
    if (!wasActiveRef.current && active) {
      setExpanded(true);
    }
    wasActiveRef.current = active;
  }, [active]);

  // 思考中内容增长时贴住底部，用户在框内往上翻则暂停跟随，翻回底部恢复
  React.useLayoutEffect(() => {
    if (!active || !followRef.current) return;
    const el = bodyRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [content, active]);

  const handleBodyScroll = () => {
    const el = bodyRef.current;
    if (!el) return;
    followRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 8;
  };

  const hasContent = Boolean(content && content.trim());
  const label = active ? "正在思考" : duration ? `已思考 ${duration} 秒` : "已深度思考";
  const canToggle = !active && hasContent;

  return (
    <div className="text-sm">
      <button
        type="button"
        onClick={() => canToggle && setExpanded((prev) => !prev)}
        disabled={!canToggle}
        aria-expanded={expanded}
        className={cn(
          "inline-flex items-center gap-1.5 rounded-md py-1 pr-1.5 text-[13px] text-[#8A8F94] transition-colors",
          canToggle ? "hover:text-[#4A4A4A]" : "cursor-default"
        )}
      >
        {active ? (
          <Loader2 className="h-3.5 w-3.5 animate-spin motion-reduce:animate-none" />
        ) : (
          <Brain className="h-3.5 w-3.5" />
        )}
        <span>{label}</span>
        {canToggle ? (
          <ChevronDown
            className={cn(
              "h-3.5 w-3.5 transition-transform duration-200 motion-reduce:transition-none",
              expanded && "rotate-180"
            )}
          />
        ) : null}
      </button>
      {/* grid 行高 0fr ↔ 1fr 过渡：无需测量真实高度即可做平滑收放 */}
      <div
        className={cn(
          "grid transition-[grid-template-rows,opacity] duration-200 ease-out motion-reduce:transition-none",
          expanded && hasContent ? "grid-rows-[1fr] opacity-100" : "grid-rows-[0fr] opacity-0"
        )}
        aria-hidden={!expanded}
      >
        <div className="min-h-0 overflow-hidden">
          <div
            ref={bodyRef}
            onScroll={handleBodyScroll}
            className={cn(
              "mt-1.5 overflow-y-auto border-l-2 border-[#E5E5E5] pl-3 text-[13px] leading-relaxed text-[#8A8F94] whitespace-pre-wrap break-words",
              active ? "max-h-[9.5rem]" : "max-h-[24rem]"
            )}
          >
            {content}
            {active ? (
              <span className="ml-0.5 inline-block h-3.5 w-1 animate-blink bg-[#C4C4C4] align-middle motion-reduce:animate-none" />
            ) : null}
          </div>
        </div>
      </div>
    </div>
  );
}
