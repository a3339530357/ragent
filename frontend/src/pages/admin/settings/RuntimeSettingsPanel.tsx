import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle
} from "@/components/ui/alert-dialog";
import { getErrorMessage } from "@/utils/error";
import {
  loadRuntimeSettings,
  saveRuntimeDraft,
  validateRuntimeDraft,
  publishRuntimeDraft,
  loadRuntimeVersions,
  rollbackRuntimeConfig,
  loadRuntimeVersion,
  testRuntimeModel,
  type ConfigValue,
  type ConfigObject,
  type RuntimeSettings,
  type ConfigVersion
} from "@/services/runtimeSettingsService";

const labels: Record<string, string> = {
  agentChat: "主 Agent 模型",
  ai: "辅助模型与供应商",
  providers: "供应商",
  chat: "聊天模型",
  embedding: "Embedding（本期只读）",
  rerank: "重排序模型",
  vlm: "视觉模型",
  provider: "供应商标识",
  model: "模型名称",
  thinkingEffort: "思考强度",
  thinkingParam: "思考参数格式",
  id: "模型标识",
  url: "服务地址",
  apiKey: "API 密钥",
  endpoints: "接口路径",
  defaultModel: "默认模型标识",
  candidates: "候选模型",
  defaultTier: "默认档位",
  deepThinkingTier: "深度思考档位",
  tiers: "调用档位",
  timeoutMs: "超时（毫秒）",
  priority: "优先级",
  enabled: "启用",
  supportsThinking: "支持思考",
  dimension: "向量维度",
  selection: "熔断策略（本期只读）",
  stream: "输出策略（本期只读）",
  ragFeatures: "问答功能",
  queryRewriteEnabled: "查询改写",
  rerankEnabled: "重排序",
  contextEnrichEnabled: "上下文富化",
  citationEnabled: "回答引用",
  ragSearch: "检索策略",
  defaultTopK: "最终资料条数",
  recallBudget: "每通道召回条数",
  scope: "检索范围",
  minIntentScore: "意图分下限",
  confidenceThreshold: "定向检索阈值",
  supplementRatio: "补充路比例",
  channels: "检索通道",
  vector: "向量",
  keyword: "关键词",
  graph: "图谱",
  webSearch: "联网检索",
  count: "结果条数",
  timeoutSeconds: "超时（秒）",
  apiUrl: "接口地址",
  fusion: "通道融合",
  strategy: "融合策略",
  rrfK: "RRF 平滑系数",
  rerankCandidateLimit: "重排序候选上限",
  channelWeights: "通道权重",
  defaultWeight: "默认权重",
  evidence: "证据过滤",
  minRerankScore: "最低精排相关度"
};
const enums: Record<string, string[]> = {
  thinkingEffort: ["", "OFF", "LOW", "MEDIUM", "HIGH"],
  thinkingParam: ["", "REASONING_EFFORT", "ENABLE_THINKING", "THINKING_OBJECT"],
  strategy: ["rrf", "off"]
};
const readOnlyPaths = new Set(["ai.embedding", "ai.selection", "ai.stream"]);
const object = (value: ConfigValue): value is ConfigObject =>
  value !== null && typeof value === "object" && !Array.isArray(value);
const title = (name: string) => labels[name] ?? name;

function SecretField({
  value,
  onChange,
  disabled
}: {
  value: ConfigObject;
  onChange: (value: ConfigValue) => void;
  disabled: boolean;
}) {
  return (
    <div className="space-y-2">
      <select
        aria-label="密钥操作"
        className="h-9 rounded-md border bg-background px-3 text-sm"
        disabled={disabled}
        value={String(value.action)}
        onChange={(e) =>
          onChange({ action: e.target.value, configured: value.configured ?? false })
        }
      >
        <option value="keep">保留{value.configured ? "已配置的密钥" : "（尚未配置）"}</option>
        <option value="replace">替换密钥</option>
        <option value="clear">清除密钥</option>
      </select>
      {value.action === "replace" && (
        <Input
          aria-label="新 API 密钥"
          type="password"
          autoComplete="new-password"
          disabled={disabled}
          value={String(value.value ?? "")}
          onChange={(e) => onChange({ ...value, value: e.target.value })}
        />
      )}
    </div>
  );
}

function Dictionary({
  value,
  path,
  onChange,
  disabled
}: {
  value: ConfigObject;
  path: string;
  onChange: (value: ConfigValue) => void;
  disabled: boolean;
}) {
  const [name, setName] = useState("");
  const add = () => {
    const id = name.trim();
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(id) || id in value) {
      toast.error("请输入不重复的字母、数字、下划线或短横线标识");
      return;
    }
    const item: ConfigValue = path.endsWith("providers")
      ? {
          url: "",
          apiKey: { action: "keep", configured: false },
          endpoints: { chat: "/v1/chat/completions" },
          thinkingParam: null
        }
      : path.endsWith("tiers")
        ? { candidates: [], timeoutMs: 30000 }
        : "";
    onChange({ ...value, [id]: item });
    setName("");
  };
  return (
    <div className="space-y-3">
      {Object.entries(value).map(([key, entry]) => (
        <div key={key} className="rounded-lg border p-3">
          <div className="mb-3 flex items-center justify-between">
            <span className="font-medium text-sm">{key}</span>
            {!disabled && (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  const next = { ...value };
                  delete next[key];
                  onChange(next);
                }}
              >
                移除
              </Button>
            )}
          </div>
          <ConfigEditor
            value={entry}
            path={`${path}.${key}`}
            disabled={disabled}
            onChange={(next) => onChange({ ...value, [key]: next })}
          />
        </div>
      ))}
      {!disabled && (
        <div className="flex gap-2">
          <Input
            aria-label="新配置项标识"
            placeholder={path.endsWith("providers") ? "新供应商标识" : "新配置项标识"}
            value={name}
            onChange={(e) => setName(e.target.value)}
          />
          <Button variant="outline" onClick={add}>
            添加
          </Button>
        </div>
      )}
    </div>
  );
}

function ConfigEditor({
  value,
  path,
  onChange,
  disabled = false
}: {
  value: ConfigValue;
  path: string;
  onChange: (value: ConfigValue) => void;
  disabled?: boolean;
}) {
  const name = path.split(".").at(-1) ?? path;
  const locked = disabled || readOnlyPaths.has(path);
  if (object(value) && "action" in value && name === "apiKey")
    return <SecretField value={value} onChange={onChange} disabled={locked} />;
  if (object(value) && (name === "providers" || name === "tiers" || name === "endpoints"))
    return <Dictionary value={value} path={path} onChange={onChange} disabled={locked} />;
  if (object(value))
    return (
      <div className="space-y-3">
        {Object.entries(value).map(([key, child]) => {
          if (readOnlyPaths.has(`${path}.${key}`))
            return (
              <p key={key} className="text-xs text-muted-foreground">
                {title(key)}：沿用部署配置
              </p>
            );
          return (
            <div
              key={key}
              className={
                object(child) || Array.isArray(child)
                  ? "space-y-2"
                  : "grid items-start gap-2 sm:grid-cols-[180px_1fr]"
              }
            >
              <span className="text-sm text-muted-foreground">{title(key)}</span>
              <ConfigEditor
                value={child}
                path={`${path}.${key}`}
                disabled={locked}
                onChange={(next) => onChange({ ...value, [key]: next })}
              />
            </div>
          );
        })}
      </div>
    );
  if (Array.isArray(value)) {
    if (path.includes(".tiers."))
      return (
        <Input
          aria-label="有序候选模型标识"
          disabled={locked}
          placeholder="模型标识，按调用顺序用逗号分隔"
          value={value.join(", ")}
          onChange={(e) =>
            onChange(
              e.target.value
                .split(",")
                .map((x) => x.trim())
                .filter(Boolean)
            )
          }
        />
      );
    return (
      <div className="space-y-3">
        {value.map((entry, i) => (
          <div key={i} className="space-y-2 rounded-lg border p-3">
            <div className="flex items-center justify-between">
              <span className="text-sm">模型 {i + 1}</span>
              {!locked && (
                <Button
                  size="sm"
                  variant="ghost"
                  onClick={() => onChange(value.filter((_, index) => index !== i))}
                >
                  移除
                </Button>
              )}
            </div>
            <ConfigEditor
              value={entry}
              path={`${path}.${i}`}
              disabled={locked}
              onChange={(next) => onChange(value.map((old, index) => (index === i ? next : old)))}
            />
          </div>
        ))}
        {!locked && (
          <Button
            variant="outline"
            size="sm"
            onClick={() =>
              onChange([
                ...value,
                {
                  id: "",
                  provider: "",
                  model: "",
                  url: null,
                  dimension: null,
                  priority: 100,
                  enabled: true,
                  supportsThinking: false
                }
              ])
            }
          >
            添加模型
          </Button>
        )}
      </div>
    );
  }
  if (typeof value === "boolean")
    return (
      <label className="flex items-center gap-2 text-sm">
        <input
          type="checkbox"
          disabled={locked}
          checked={value}
          onChange={(e) => onChange(e.target.checked)}
          aria-label={title(name)}
        />
        {value ? "已开启" : "已关闭"}
      </label>
    );
  if (enums[name])
    return (
      <select
        aria-label={title(name)}
        disabled={locked}
        className="h-9 rounded-md border bg-background px-3 text-sm"
        value={String(value ?? "")}
        onChange={(e) => onChange(e.target.value || null)}
      >
        {enums[name].map((option) => (
          <option key={option} value={option}>
            {option || "沿用模型默认"}
          </option>
        ))}
      </select>
    );
  return (
    <Input
      aria-label={title(name)}
      disabled={locked || name === "dimension"}
      type={typeof value === "number" ? "number" : "text"}
      step="any"
      value={value === null ? "" : String(value)}
      onChange={(e) =>
        onChange(typeof value === "number" ? Number(e.target.value) : e.target.value || null)
      }
    />
  );
}

function changedFields(before: ConfigValue, after: ConfigValue, prefix = ""): string[] {
  if (JSON.stringify(before) === JSON.stringify(after)) return [];
  if (prefix.endsWith("apiKey")) return [prefix];
  if (object(before) && object(after))
    return [...new Set([...Object.keys(before), ...Object.keys(after)])].flatMap((key) =>
      changedFields(before[key] ?? null, after[key] ?? null, prefix ? `${prefix}.${key}` : key)
    );
  return [prefix];
}

export function RuntimeSettingsPanel({ onPublished }: { onPublished: () => void }) {
  const [remote, setRemote] = useState<RuntimeSettings | null>(null);
  const [edit, setEdit] = useState<ConfigObject | null>(null);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [validation, setValidation] = useState<string[] | null>(null);
  const [versions, setVersions] = useState<ConfigVersion[]>([]);
  const [preview, setPreview] = useState<ConfigObject | null>(null);
  const [confirm, setConfirm] = useState<"publish" | number | null>(null);
  const [capability, setCapability] = useState("agent");
  const [modelId, setModelId] = useState("");
  const [tab, setTab] = useState("agentChat");
  const [instances, setInstances] = useState<RuntimeSettings["instances"]>([]);
  const [newer, setNewer] = useState(false);

  const reload = async () => {
    const [data, history] = await Promise.all([loadRuntimeSettings(), loadRuntimeVersions()]);
    setRemote(data);
    setEdit(data.draft ?? data.effective);
    setVersions(history);
    setInstances(data.instances);
    setDirty(false);
    setValidation(null);
    setNewer(false);
    setError(null);
    if (!data.effective.agentChat) {
      setTab("ai");
      setCapability("chat");
    }
  };
  useEffect(() => {
    void reload().catch((e) => setError(getErrorMessage(e, "加载运行时配置失败")));
  }, []);
  useEffect(() => {
    if (!remote) return;
    const timer = window.setInterval(() => {
      void loadRuntimeSettings()
        .then((next) => {
          setInstances(next.instances);
          setNewer(
            next.activeVersion !== remote.activeVersion ||
              next.draftRevision !== remote.draftRevision
          );
        })
        .catch(() => {
          /* 保留当前表单，下一次轮询重试 */
        });
    }, 5000);
    return () => window.clearInterval(timer);
  }, [remote]);

  const run = async (action: () => Promise<void>) => {
    setBusy(true);
    setError(null);
    try {
      await action();
    } catch (e) {
      setError(getErrorMessage(e, "配置操作失败"));
    } finally {
      setBusy(false);
    }
  };
  if (!remote || !edit)
    return (
      <section className="rounded-xl border p-4">
        <p>{error ?? "正在加载运行时配置…"}</p>
        {error && (
          <Button variant="outline" onClick={() => void run(reload)}>
            重试
          </Button>
        )}
      </section>
    );
  const revision = { draftRevision: remote.draftRevision, baseVersion: remote.activeVersion };
  const diffs = [...new Set([...(remote.changes ?? []), ...changedFields(remote.effective, edit)])];
  const tabs = [
    ...(edit.agentChat ? ["agentChat"] : []),
    "ai",
    "ragFeatures",
    "ragSearch",
    "history"
  ];
  const writable = remote.writable && !busy;
  const save = () =>
    run(async () => {
      await saveRuntimeDraft(revision, edit);
      await reload();
      toast.success("草稿已保存，尚未发布");
    });
  const validate = () =>
    run(async () => {
      const errors = await validateRuntimeDraft(revision);
      setValidation(errors);
      if (!errors.length) toast.success("配置校验通过");
    });
  const publish = () =>
    run(async () => {
      if (confirm === "publish") await publishRuntimeDraft(revision);
      else if (typeof confirm === "number") await rollbackRuntimeConfig(revision, confirm);
      setConfirm(null);
      await reload();
      onPublished();
      toast.success("配置已发布，新请求使用新版本");
    });

  return (
    <section className="space-y-4 rounded-xl border bg-card p-5">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h2 className="font-semibold">运行时配置</h2>
          <p className="mt-1 text-sm text-muted-foreground">
            已发布版本 {remote.activeVersion} · 草稿版本 {remote.draftRevision} ·{" "}
            {dirty ? "有未保存修改" : remote.draft ? "有未发布草稿" : "与已发布配置一致"}
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button variant="outline" disabled={busy || dirty} onClick={() => void run(reload)}>
            刷新配置
          </Button>
          <Button
            variant="outline"
            disabled={!writable || !dirty || newer}
            onClick={() => void save()}
          >
            保存草稿
          </Button>
          <Button
            variant="outline"
            disabled={!writable || dirty || !remote.draft || newer}
            onClick={() => void validate()}
          >
            校验草稿
          </Button>
          <Button
            disabled={
              !writable ||
              dirty ||
              newer ||
              !remote.draft ||
              validation === null ||
              validation.length > 0
            }
            onClick={() => setConfirm("publish")}
          >
            发布配置
          </Button>
        </div>
      </div>
      <p className="text-sm text-muted-foreground">
        发布后新请求生效，正在回答的会话继续使用原版本。Embedding、部署后端、记忆和限流策略本期保持只读。
      </p>
      {!remote.writable && (
        <p className="text-sm text-amber-700">尚未配置服务器加密主密钥，当前只支持查看。</p>
      )}
      {newer && (
        <div className="text-sm text-amber-700">
          其他管理员已更新配置。请刷新后重新编辑，当前修改不会自动覆盖服务器。
          <Button variant="ghost" size="sm" disabled={busy} onClick={() => void run(reload)}>
            放弃本地修改并刷新
          </Button>
        </div>
      )}
      {error && confirm === null && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      {validation && validation.length > 0 && (
        <ul role="alert" className="list-disc pl-5 text-sm text-destructive">
          {validation.map((message, i) => (
            <li key={i}>{message}</li>
          ))}
        </ul>
      )}
      <div className="flex flex-wrap gap-2 border-b pb-3">
        {tabs.map((key) => (
          <Button
            key={key}
            variant={tab === key ? "default" : "ghost"}
            size="sm"
            onClick={() => setTab(key)}
          >
            {key === "history" ? "版本历史" : title(key)}
          </Button>
        ))}
      </div>
      {tab !== "history" && edit[tab] !== undefined && (
        <fieldset disabled={!writable} className="min-w-0">
          <ConfigEditor
            value={edit[tab]}
            path={tab}
            disabled={!writable}
            onChange={(next) => {
              setEdit({ ...edit, [tab]: next });
              setDirty(true);
              setValidation(null);
            }}
          />
        </fieldset>
      )}
      {tab === "history" && (
        <div className="space-y-3">
          <p className="text-sm text-muted-foreground">
            回滚会生成新版本，并替换当前草稿。最近显示 50 个版本。
          </p>
          {versions.map((version) => (
            <div
              key={version.id}
              className="flex flex-wrap items-center gap-3 rounded-lg border p-3 text-sm"
            >
              <span>版本 {version.id}</span>
              <span>{version.createdBy}</span>
              <span>{version.createTime}</span>
              {version.rollbackFrom != null && <span>回滚自 {version.rollbackFrom}</span>}
              <Button
                size="sm"
                variant="outline"
                disabled={busy}
                onClick={() =>
                  void run(async () => setPreview(await loadRuntimeVersion(version.id)))
                }
              >
                查看
              </Button>
              <Button
                size="sm"
                variant="outline"
                disabled={!writable || newer || dirty || version.id === remote.activeVersion}
                onClick={() => setConfirm(version.id)}
              >
                回滚至此版本
              </Button>
            </div>
          ))}
          <Button
            size="sm"
            variant="outline"
            disabled={!writable || newer || dirty}
            onClick={() => setConfirm(0)}
          >
            恢复部署默认配置
          </Button>
          {preview && (
            <div className="rounded-lg border p-4">
              <ConfigEditor value={preview} path="" disabled onChange={() => {}} />
            </div>
          )}
        </div>
      )}
      <details className="text-sm">
        <summary className="cursor-pointer">本次变更字段（{diffs.length}）</summary>
        <ul className="mt-2 list-disc space-y-1 pl-5 text-muted-foreground">
          {diffs.map((path) => (
            <li key={path}>{path.split(".").map(title).join(" / ")}</li>
          ))}
        </ul>
      </details>
      <details className="rounded-lg border p-3">
        <summary className="cursor-pointer text-sm">模型连接测试</summary>
        <p className="my-3 text-sm text-muted-foreground">
          测试已保存的草稿；没有草稿时测试已发布配置。会向所选服务发送固定测试内容，可能产生少量调用费用。
        </p>
        <div className="flex flex-wrap gap-2">
          <select
            aria-label="测试能力"
            value={capability}
            onChange={(e) => setCapability(e.target.value)}
            className="rounded-md border bg-background px-3 text-sm"
          >
            {edit.agentChat && <option value="agent">主 Agent</option>}
            <option value="chat">聊天</option>
            <option value="rerank">重排序</option>
            <option value="vlm">视觉模型</option>
          </select>
          {capability !== "agent" && (
            <Input
              className="max-w-xs"
              placeholder="模型标识"
              value={modelId}
              onChange={(e) => setModelId(e.target.value)}
            />
          )}
          <Button
            variant="outline"
            disabled={busy || dirty || newer || (capability !== "agent" && !modelId)}
            onClick={() =>
              void run(async () => {
                await testRuntimeModel(revision, capability, modelId);
                toast.success("连接测试通过");
              })
            }
          >
            发送测试请求
          </Button>
        </div>
      </details>
      <div className="space-y-1 text-xs text-muted-foreground">
        <p>实例应用状态（约每 5 秒刷新）</p>
        {instances.map((instance) => (
          <p key={instance.instanceId}>
            {instance.instanceId.slice(0, 8)}：当前版本 {instance.appliedVersion} / 目标版本{" "}
            {instance.targetVersion}
            {instance.error
              ? ` · ${instance.error}`
              : instance.appliedVersion === instance.targetVersion
                ? " · 已同步"
                : " · 等待应用"}
          </p>
        ))}
      </div>
      <AlertDialog
        open={confirm !== null}
        onOpenChange={(open) => {
          if (!open && !busy) setConfirm(null);
        }}
      >
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>
              {confirm === "publish" ? "发布配置？" : "回滚配置？"}
            </AlertDialogTitle>
            <AlertDialogDescription>
              {confirm === "publish"
                ? "发布后，新请求将使用已校验的草稿配置。"
                : `将版本 ${confirm} 的配置重新发布，并清除共享草稿。`}
              正在执行的问答不受影响。
            </AlertDialogDescription>
          </AlertDialogHeader>
          {error && (
            <p role="alert" className="text-sm text-destructive">
              {error}
            </p>
          )}
          <AlertDialogFooter>
            <AlertDialogCancel disabled={busy}>取消</AlertDialogCancel>
            <AlertDialogAction
              disabled={busy}
              onClick={(event) => {
                event.preventDefault();
                void publish();
              }}
            >
              确认
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </section>
  );
}
