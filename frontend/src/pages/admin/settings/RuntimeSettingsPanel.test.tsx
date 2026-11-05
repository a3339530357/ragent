import { afterEach, beforeEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { RuntimeSettingsPanel } from "./RuntimeSettingsPanel";
import * as service from "@/services/runtimeSettingsService";
import type { ConfigObject, RuntimeSettings } from "@/services/runtimeSettingsService";
vi.mock("@/services/runtimeSettingsService", () => ({
  loadRuntimeSettings: vi.fn(),
  saveRuntimeDraft: vi.fn(),
  validateRuntimeDraft: vi.fn(),
  publishRuntimeDraft: vi.fn(),
  loadRuntimeVersions: vi.fn(),
  rollbackRuntimeConfig: vi.fn(),
  loadRuntimeVersion: vi.fn(),
  testRuntimeModel: vi.fn()
}));
vi.mock("sonner", () => ({ toast: { success: vi.fn(), error: vi.fn() } }));
let server: RuntimeSettings;
const button = (name: string) => screen.getByRole("button", { name }) as HTMLButtonElement;
const config = (): ConfigObject => ({
  agentChat: { provider: "gateway", model: "old-model", thinkingEffort: null },
  ai: {
    providers: {
      gateway: {
        url: "https://example.test",
        apiKey: { action: "keep", configured: true },
        endpoints: { chat: "/v1/chat/completions" },
        thinkingParam: null
      }
    },
    chat: { candidates: [], tiers: {}, defaultTier: "standard" },
    embedding: {},
    selection: {},
    stream: {}
  },
  ragFeatures: {
    queryRewriteEnabled: true,
    rerankEnabled: true,
    citationEnabled: false,
    contextEnrichEnabled: true
  },
  ragSearch: { defaultTopK: 10, recallBudget: 20 }
});
beforeEach(() => {
  vi.resetAllMocks();
  server = {
    activeVersion: 3,
    appliedVersion: 3,
    draftRevision: 5,
    draftBaseVersion: 3,
    writable: true,
    effective: config(),
    draft: null,
    changes: [],
    instances: []
  };
  vi.mocked(service.loadRuntimeSettings).mockImplementation(async () => structuredClone(server));
  vi.mocked(service.loadRuntimeVersions).mockResolvedValue([
    { id: 2, createdBy: "admin", createTime: "2026-11-05 10:00:00" }
  ]);
  vi.mocked(service.saveRuntimeDraft).mockImplementation(async (_revision, document) => {
    server.draftRevision++;
    server.draft = structuredClone(document);
    const providers = (server.draft.ai as ConfigObject).providers as ConfigObject;
    for (const provider of Object.values(providers))
      (provider as ConfigObject).apiKey = { action: "keep", configured: true };
    return server.draftRevision;
  });
  vi.mocked(service.validateRuntimeDraft).mockResolvedValue([]);
  vi.mocked(service.publishRuntimeDraft).mockImplementation(async () => {
    server.activeVersion++;
    server.appliedVersion = server.activeVersion;
    server.draftRevision++;
    server.effective = server.draft ?? server.effective;
    server.draft = null;
    return server.activeVersion;
  });
  vi.mocked(service.rollbackRuntimeConfig).mockResolvedValue(4);
  vi.mocked(service.testRuntimeModel).mockResolvedValue(undefined);
});
afterEach(cleanup);
async function mount() {
  const onPublished = vi.fn();
  const result = render(<RuntimeSettingsPanel onPublished={onPublished} />);
  await screen.findByRole("textbox", { name: "模型名称" });
  return { ...result, onPublished };
}
async function editAndSave() {
  fireEvent.change(screen.getByRole("textbox", { name: "模型名称" }), {
    target: { value: "new-model" }
  });
  fireEvent.click(button("保存草稿"));
  await waitFor(() => expect(button("校验草稿").disabled).toBe(false));
}
it("保存和校验不会发布，确认后才提交已保存版本", async () => {
  const { onPublished } = await mount();
  expect(button("发布配置").disabled).toBe(true);
  await editAndSave();
  expect(service.saveRuntimeDraft).toHaveBeenCalledWith(
    { draftRevision: 5, baseVersion: 3 },
    expect.objectContaining({ agentChat: expect.objectContaining({ model: "new-model" }) })
  );
  expect(service.publishRuntimeDraft).not.toHaveBeenCalled();
  fireEvent.click(button("校验草稿"));
  await waitFor(() => expect(button("发布配置").disabled).toBe(false));
  fireEvent.click(button("发布配置"));
  expect(service.publishRuntimeDraft).not.toHaveBeenCalled();
  fireEvent.click(button("确认"));
  await waitFor(() =>
    expect(service.publishRuntimeDraft).toHaveBeenCalledWith({ draftRevision: 6, baseVersion: 3 })
  );
  await waitFor(() => expect(onPublished).toHaveBeenCalledOnce());
});
it("校验失败显示原因并禁止发布", async () => {
  await mount();
  await editAndSave();
  vi.mocked(service.validateRuntimeDraft).mockResolvedValue(["召回预算不能小于最终条数"]);
  fireEvent.click(button("校验草稿"));
  await screen.findByText("召回预算不能小于最终条数");
  expect(button("发布配置").disabled).toBe(true);
});
it("冲突时保留本地修改，不会覆盖发布版本", async () => {
  await mount();
  vi.mocked(service.saveRuntimeDraft).mockRejectedValue(
    new Error("配置版本冲突，请刷新后重新编辑")
  );
  fireEvent.change(screen.getByRole("textbox", { name: "模型名称" }), {
    target: { value: "mine" }
  });
  fireEvent.click(button("保存草稿"));
  await screen.findByText("配置版本冲突，请刷新后重新编辑");
  expect((screen.getByRole("textbox", { name: "模型名称" }) as HTMLInputElement).value).toBe(
    "mine"
  );
  expect(service.publishRuntimeDraft).not.toHaveBeenCalled();
});
it("密钥仅以密码输入替换，保存后清除表单中的明文", async () => {
  const { container } = await mount();
  fireEvent.click(button("辅助模型与供应商"));
  fireEvent.change(screen.getByRole("combobox", { name: "密钥操作" }), {
    target: { value: "replace" }
  });
  const secret = screen.getByLabelText("新 API 密钥") as HTMLInputElement;
  expect(secret.type).toBe("password");
  fireEvent.change(secret, { target: { value: "new-private-key" } });
  expect(container.textContent).not.toContain("new-private-key");
  fireEvent.click(button("保存草稿"));
  await waitFor(() => expect(service.saveRuntimeDraft).toHaveBeenCalled());
  const sent = vi.mocked(service.saveRuntimeDraft).mock.calls[0][1];
  expect(
    (((sent.ai as ConfigObject).providers as ConfigObject).gateway as ConfigObject).apiKey
  ).toEqual({ action: "replace", configured: true, value: "new-private-key" });
  await waitFor(() => expect(screen.queryByLabelText("新 API 密钥")).toBeNull());
});
it("历史版本回滚需要确认且携带当前版本", async () => {
  await mount();
  fireEvent.click(button("版本历史"));
  fireEvent.click(button("回滚至此版本"));
  expect(service.rollbackRuntimeConfig).not.toHaveBeenCalled();
  fireEvent.click(button("确认"));
  await waitFor(() =>
    expect(service.rollbackRuntimeConfig).toHaveBeenCalledWith(
      { draftRevision: 5, baseVersion: 3 },
      2
    )
  );
});
it("缺少服务器主密钥时编辑和发布均不可用", async () => {
  server.writable = false;
  await mount();
  expect((screen.getByRole("textbox", { name: "模型名称" }) as HTMLInputElement).disabled).toBe(
    true
  );
  expect(button("保存草稿").disabled).toBe(true);
  expect(button("发布配置").disabled).toBe(true);
});
it("加载和编辑不会调用模型，测试必须显式触发", async () => {
  await mount();
  expect(service.testRuntimeModel).not.toHaveBeenCalled();
  fireEvent.click(screen.getByText("模型连接测试"));
  fireEvent.click(button("发送测试请求"));
  await waitFor(() =>
    expect(service.testRuntimeModel).toHaveBeenCalledWith(
      { draftRevision: 5, baseVersion: 3 },
      "agent",
      ""
    )
  );
});

it("发布失败时错误在确认框中可见，保留草稿", async () => {
  const { onPublished } = await mount();
  await editAndSave();
  fireEvent.click(button("校验草稿"));
  await waitFor(() => expect(button("发布配置").disabled).toBe(false));
  vi.mocked(service.publishRuntimeDraft).mockRejectedValue(new Error("配置版本冲突，请刷新"));
  fireEvent.click(button("发布配置"));
  fireEvent.click(button("确认"));
  const message = await screen.findByRole("alert");
  expect(message.textContent).toContain("配置版本冲突");
  expect(screen.getByRole("alertdialog").contains(message)).toBe(true);
  expect(onPublished).not.toHaveBeenCalled();
  expect(server.draft).not.toBeNull();
});
