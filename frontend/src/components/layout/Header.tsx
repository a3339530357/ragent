import * as React from "react";
import { FoldHorizontal, Github, Menu, UnfoldHorizontal } from "lucide-react";

import { Button } from "@/components/ui/button";
import { useGitHubStars } from "@/hooks/useGitHubStars";
import { cn } from "@/lib/utils";
import { useChatStore } from "@/stores/chatStore";
import { useLayoutStore } from "@/stores/layoutStore";

interface HeaderProps {
  onToggleSidebar: () => void;
}

export function Header({ onToggleSidebar }: HeaderProps) {
  const { currentSessionId, sessions } = useChatStore();
  const { wideMode, toggleWideMode } = useLayoutStore();
  const starCount = useGitHubStars();
  const currentSession = React.useMemo(
    () => sessions.find((session) => session.id === currentSessionId),
    [sessions, currentSessionId]
  );

  const starLabel = React.useMemo(() => {
    if (starCount === null) return "--";
    if (starCount < 1000) return String(starCount);
    const rounded = Math.round((starCount / 1000) * 10) / 10;
    const text = String(rounded).replace(/\.0$/, "");
    return `${text}k`;
  }, [starCount]);

  return (
    <header className="sticky top-0 z-20 bg-white">
      <div className="flex h-16 items-center justify-between px-6">
        <div className="flex items-center gap-2">
          <Button
            variant="ghost"
            size="icon"
            onClick={onToggleSidebar}
            aria-label="切换侧边栏"
            className="text-gray-500 hover:bg-gray-100 lg:hidden"
          >
            <Menu className="h-5 w-5" />
          </Button>
          <p className="text-base font-medium text-gray-900">
            {currentSession?.title || "新对话"}
          </p>
        </div>
        <div className="flex items-center gap-2">
          {/* 小屏下内容本来就占满，宽屏开关没有意义，只在 lg 以上显示 */}
          <button
            type="button"
            onClick={toggleWideMode}
            aria-pressed={wideMode}
            title={wideMode ? "切回标准宽度" : "切换到宽屏模式"}
            className={cn(
              "hidden items-center gap-1.5 rounded-xl border px-3 py-1.5 text-sm transition-colors active:scale-95 lg:inline-flex",
              wideMode
                ? "border-[#BFDBFE] bg-[#EFF6FF] text-[#2563EB] hover:bg-[#DBEAFE]"
                : "border-gray-200 text-gray-600 hover:bg-gray-100 hover:text-gray-900"
            )}
          >
            {wideMode ? (
              <FoldHorizontal className="h-4 w-4" />
            ) : (
              <UnfoldHorizontal className="h-4 w-4" />
            )}
            <span className="font-medium">宽屏</span>
          </button>
          <a
            href="https://github.com/nageoffer/ragent"
            target="_blank"
            rel="noreferrer"
            className="flex items-center gap-2 rounded-xl border border-gray-200 px-3 py-1.5 text-sm text-gray-600 transition hover:bg-gray-100 hover:text-gray-900"
            aria-label="打开 GitHub 仓库"
          >
            <Github className="h-4 w-4" />
            <span className="font-medium">Star</span>
            <span className="rounded-full bg-gray-100 px-2 py-0.5 text-xs text-gray-600">
              {starLabel}
            </span>
          </a>
        </div>
      </div>
    </header>
  );
}
