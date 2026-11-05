import * as React from "react";
import { CircleStop, RotateCw, TriangleAlert } from "lucide-react";

import { FeedbackButtons } from "@/components/chat/FeedbackButtons";
import { MarkdownRenderer } from "@/components/chat/MarkdownRenderer";
import { RecommendedQuestions } from "@/components/chat/RecommendedQuestions";
import { RecommendedQuestionsButton } from "@/components/chat/RecommendedQuestionsButton";
import { SourcesButton } from "@/components/chat/SourcesButton";
import { ThinkingIndicator } from "@/components/chat/ThinkingIndicator";
import { useChatStore } from "@/stores/chatStore";
import type { Message } from "@/types";

interface MessageItemProps {
  message: Message;
}

export const MessageItem = React.memo(function MessageItem({ message }: MessageItemProps) {
  const isUser = message.role === "user";
  const showFeedback =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    message.id &&
    !message.id.startsWith("assistant-");
  const isThinking = Boolean(message.isThinking);
  const hasSources =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    (message.sources?.length ?? 0) > 0;
  // 推荐追问：完成态且已落库的助手消息（真实 messageId）方可触发 与反馈按钮判据一致
  const canRecommend =
    message.role === "assistant" &&
    message.status !== "streaming" &&
    Boolean(message.id) &&
    (message.messageStatus ?? "NORMAL") === "NORMAL" &&
    !message.id.startsWith("assistant-");
  const hasThinking = Boolean(message.thinking && message.thinking.trim().length > 0);
  const hasContent = message.content.trim().length > 0;
  const isWaiting = message.status === "streaming" && !isThinking && !hasContent;
  const isError = message.status === "error";
  // 本轮手动停止（cancelled）与历史里被中断落库的回答（INTERRUPTED）统一显示「已停止」标签
  const isStopped =
    !isError &&
    message.status !== "streaming" &&
    (message.status === "cancelled" || message.messageStatus === "INTERRUPTED");
  const retryMessage = useChatStore((state) => state.retryMessage);
  const isStreamingAny = useChatStore((state) => state.isStreaming);
  // 只允许重试最后一条：重试会撤掉这一轮再重发，中间轮次撤掉会打乱上下文
  const isLast = useChatStore(
    (state) => state.messages[state.messages.length - 1]?.id === message.id
  );
  const canRetry = isError && isLast && !isStreamingAny;

  if (isUser) {
    return (
      <div className="flex">
        <div className="user-message">
          <p className="whitespace-pre-wrap break-words">{message.content}</p>
        </div>
      </div>
    );
  }

  return (
    <div className="group flex">
      <div className="min-w-0 flex-1 space-y-3">
        {isThinking || hasThinking ? (
          <ThinkingIndicator
            content={message.thinking}
            active={isThinking}
            duration={message.thinkingDuration}
          />
        ) : null}
        <div className="space-y-2">
          {isWaiting ? (
            <div className="ai-wait" aria-label="思考中">
              <span className="ai-wait-dots" aria-hidden="true">
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
                <span className="ai-wait-dot" />
              </span>
            </div>
          ) : null}
          {hasContent ? (
            <MarkdownRenderer
              content={message.content}
              messageId={message.id}
              sources={message.sources}
            />
          ) : null}
          {isError ? (
            <div
              role="alert"
              className="flex flex-wrap items-center gap-x-3 gap-y-1.5 rounded-lg border border-[#FDE2E2] bg-[#FFF7F7] px-3 py-2 text-[13px] text-[#B42318]"
            >
              <span className="inline-flex min-w-0 items-center gap-1.5">
                <TriangleAlert className="h-3.5 w-3.5 shrink-0" />
                <span className="break-words">
                  {hasContent ? "回答没有生成完：" : "没能生成回答："}
                  {message.errorMessage || "生成失败，请重试"}
                </span>
              </span>
              {canRetry ? (
                <button
                  type="button"
                  onClick={() => void retryMessage(message.id)}
                  className="inline-flex items-center gap-1 rounded-md px-2 py-1 text-xs font-medium text-[#B42318] transition-colors hover:bg-[#FDE2E2] active:scale-95"
                >
                  <RotateCw className="h-3 w-3" />
                  重试
                </button>
              ) : null}
            </div>
          ) : null}
          {isStopped ? (
            <span className="inline-flex items-center gap-1 rounded-full bg-[#F5F5F5] px-2 py-0.5 text-xs text-[#8A8F94]">
              <CircleStop className="h-3 w-3" />
              已停止生成
            </span>
          ) : null}
          {showFeedback || hasSources || canRecommend ? (
            <div className="flex items-center gap-2">
              {showFeedback ? (
                <FeedbackButtons
                  messageId={message.id}
                  feedback={message.feedback ?? null}
                  content={message.content}
                  alwaysVisible
                />
              ) : null}
              {hasSources ? (
                <SourcesButton messageId={message.id} sources={message.sources!} />
              ) : null}
              {canRecommend ? <RecommendedQuestionsButton message={message} /> : null}
            </div>
          ) : null}
          {canRecommend ? <RecommendedQuestions message={message} /> : null}
        </div>
      </div>
    </div>
  );
});
