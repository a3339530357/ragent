import { create } from "zustand";

import { storage } from "@/utils/storage";

/**
 * 对话页布局偏好（普通对话页与 Agent 对话页共用）
 * 宽屏模式：对话内容和输入框从标准列宽放宽到 1280px，选择存在浏览器本地
 */
interface LayoutState {
  wideMode: boolean;
  toggleWideMode: () => void;
}

export const useLayoutStore = create<LayoutState>((set, get) => ({
  wideMode: storage.getChatWide(),
  toggleWideMode: () => {
    const next = !get().wideMode;
    storage.setChatWide(next);
    set({ wideMode: next });
  }
}));
