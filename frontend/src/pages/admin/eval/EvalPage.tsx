import { useEffect, useMemo, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import {
  BarChart3,
  Code2,
  Eye,
  FileText,
  FileQuestion,
  Pencil,
  Play,
  Plus,
  RefreshCw,
  Search,
  Target,
  Trash2,
  Wrench,
  X
} from "lucide-react";
import { toast } from "sonner";

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
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Card, CardContent } from "@/components/ui/card";
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow
} from "@/components/ui/table";
import { Textarea } from "@/components/ui/textarea";
import { PageSizeSelect } from "@/components/admin/PageSizeSelect";
import { PAGE_SIZE_OPTIONS } from "@/components/admin/pagination";
import { RelativeTime } from "@/components/RelativeTime";
import {
  createEvalCase,
  createEvalSuite,
  deleteEvalCase,
  deleteEvalRun,
  deleteEvalSuite,
  getEvalCases,
  getEvalRun,
  getEvalRuns,
  getEvalSuites,
  runEvalSuite,
  updateEvalCase,
  updateEvalSuite,
  type EvalCase,
  type EvalRun,
  type EvalRunDetail,
  type EvalSuite
} from "@/services/evalService";
import type { PageResult } from "@/services/ragTraceService";
import { getErrorMessage } from "@/utils/error";

const defaultGroundTruth = `{
  "referenceDocIds": [],
  "expectedIntentLeafIds": [],
  "requiredFacts": [],
  "forbiddenClaims": []
}`;

type GroundTruthTool = {
  name: string;
  argsText: string;
};

type GroundTruthForm = {
  referenceDocIds: string[];
  expectedIntentLeafIds: string[];
  requiredFacts: string[];
  forbiddenClaims: string[];
  expectedTool: GroundTruthTool;
  expectedAnswer: string;
};

const createEmptyGroundTruthForm = (): GroundTruthForm => ({
  referenceDocIds: [],
  expectedIntentLeafIds: [],
  requiredFacts: [],
  forbiddenClaims: [],
  expectedTool: {
    name: "",
    argsText: ""
  },
  expectedAnswer: ""
});

const emptySuiteForm = {
  name: "",
  description: "",
  version: "v1"
};

const createEmptyCaseForm = () => ({
  question: "",
  category: "",
  groundTruth: createEmptyGroundTruthForm(),
  enabled: 1
});

type SuiteDialogState = {
  open: boolean;
  mode: "create" | "edit";
  item: EvalSuite | null;
};

type CaseDialogState = {
  open: boolean;
  mode: "create" | "edit";
  item: EvalCase | null;
};

const asStringArray = (value: unknown) => {
  if (!Array.isArray(value)) {
    return [];
  }
  return value
    .map((item) => String(item ?? "").trim())
    .filter(Boolean);
};

const parseGroundTruth = (raw?: string | null): GroundTruthForm => {
  if (!raw?.trim()) {
    return createEmptyGroundTruthForm();
  }
  try {
    const parsed = JSON.parse(raw) as Record<string, unknown>;
    const expectedTool = parsed.expectedTool && typeof parsed.expectedTool === "object"
      ? parsed.expectedTool as Record<string, unknown>
      : null;
    const args = expectedTool?.args;
    return {
      referenceDocIds: asStringArray(parsed.referenceDocIds),
      expectedIntentLeafIds: asStringArray(parsed.expectedIntentLeafIds),
      requiredFacts: asStringArray(parsed.requiredFacts),
      forbiddenClaims: asStringArray(parsed.forbiddenClaims),
      expectedTool: {
        name: String(expectedTool?.name ?? "").trim(),
        argsText: args && typeof args === "object" ? JSON.stringify(args, null, 2) : ""
      },
      expectedAnswer: String(parsed.expectedAnswer ?? "").trim()
    };
  } catch {
    return createEmptyGroundTruthForm();
  }
};

const parseJsonObject = (raw: string) => {
  const value = raw.trim();
  if (!value) {
    return undefined;
  }
  const parsed = JSON.parse(value) as unknown;
  if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
    throw new Error("工具参数必须是 JSON 对象");
  }
  return parsed as Record<string, unknown>;
};

const buildGroundTruthPayload = (form: GroundTruthForm) => {
  const payload: Record<string, unknown> = {};
  if (form.referenceDocIds.length > 0) {
    payload.referenceDocIds = form.referenceDocIds;
  }
  if (form.expectedIntentLeafIds.length > 0) {
    payload.expectedIntentLeafIds = form.expectedIntentLeafIds;
  }
  if (form.requiredFacts.length > 0) {
    payload.requiredFacts = form.requiredFacts;
  }
  if (form.forbiddenClaims.length > 0) {
    payload.forbiddenClaims = form.forbiddenClaims;
  }
  const toolName = form.expectedTool.name.trim();
  if (toolName) {
    const expectedTool: Record<string, unknown> = { name: toolName };
    const args = parseJsonObject(form.expectedTool.argsText);
    if (args) {
      expectedTool.args = args;
    }
    payload.expectedTool = expectedTool;
  }
  const expectedAnswer = form.expectedAnswer.trim();
  if (expectedAnswer) {
    payload.expectedAnswer = expectedAnswer;
  }
  return payload;
};

const serializeGroundTruth = (form: GroundTruthForm) => {
  const payload = buildGroundTruthPayload(form);
  if (Object.keys(payload).length === 0) {
    return "";
  }
  return JSON.stringify(payload, null, 2);
};

const groundTruthSummary = (raw?: string | null) => {
  const form = parseGroundTruth(raw);
  const items = [
    form.referenceDocIds.length ? `文档 ${form.referenceDocIds.length}` : "",
    form.expectedIntentLeafIds.length ? `意图 ${form.expectedIntentLeafIds.length}` : "",
    form.requiredFacts.length ? `事实 ${form.requiredFacts.length}` : "",
    form.forbiddenClaims.length ? `禁用 ${form.forbiddenClaims.length}` : "",
    form.expectedTool.name ? "工具" : "",
    form.expectedAnswer ? "参考答案" : ""
  ].filter(Boolean);
  return items.length > 0 ? items.join(" / ") : "未配置";
};

const formatPercent = (value?: number | null) => {
  if (!Number.isFinite(Number(value))) {
    return "0%";
  }
  return `${Math.round(Number(value) * 1000) / 10}%`;
};

const formatDuration = (value?: number | null) => {
  const duration = Number(value ?? 0);
  if (!Number.isFinite(duration) || duration <= 0) {
    return "-";
  }
  if (duration < 1000) {
    return `${Math.round(duration)}ms`;
  }
  return `${(duration / 1000).toFixed(2)}s`;
};

const statusBadge = (status?: string | null) => {
  const normalized = (status || "").toUpperCase();
  if (normalized === "SUCCESS") {
    return <Badge className="border-emerald-200 bg-emerald-50 text-emerald-700">成功</Badge>;
  }
  if (normalized === "ERROR") {
    return <Badge variant="destructive">失败</Badge>;
  }
  if (normalized === "RUNNING") {
    return <Badge className="border-amber-200 bg-amber-50 text-amber-700">运行中</Badge>;
  }
  return <Badge variant="secondary">{status || "-"}</Badge>;
};

type StringListEditorProps = {
  icon: ReactNode;
  label: string;
  metric: string;
  placeholder: string;
  values: string[];
  onChange: (values: string[]) => void;
};

function StringListEditor({ icon, label, metric, placeholder, values, onChange }: StringListEditorProps) {
  const [draft, setDraft] = useState("");

  const addValue = () => {
    const next = draft.trim();
    if (!next || values.includes(next)) {
      setDraft("");
      return;
    }
    onChange([...values, next]);
    setDraft("");
  };

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <span className="rounded-md bg-slate-100 p-1.5 text-slate-500">{icon}</span>
          <label className="text-sm font-medium text-slate-900">{label}</label>
        </div>
        <Badge variant="outline" className="text-[11px]">{metric}</Badge>
      </div>
      <div className="mt-3 flex gap-2">
        <Input
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter") {
              event.preventDefault();
              addValue();
            }
          }}
          placeholder={placeholder}
        />
        <Button type="button" variant="outline" onClick={addValue}>
          <Plus className="h-4 w-4" />
        </Button>
      </div>
      <div className="mt-3 flex min-h-8 flex-wrap gap-2">
        {values.length === 0 ? (
          <span className="text-xs text-slate-400">未配置</span>
        ) : values.map((value) => (
          <Badge key={value} variant="secondary" className="gap-1 pr-1">
            <span className="max-w-[260px] truncate">{value}</span>
            <button
              type="button"
              aria-label={`移除 ${value}`}
              className="rounded-sm p-0.5 text-slate-500 hover:bg-slate-200 hover:text-slate-900"
              onClick={() => onChange(values.filter((item) => item !== value))}
            >
              <X className="h-3 w-3" />
            </button>
          </Badge>
        ))}
      </div>
    </div>
  );
}

type GroundTruthEditorProps = {
  value: GroundTruthForm;
  onChange: (value: GroundTruthForm) => void;
};

function GroundTruthEditor({ value, onChange }: GroundTruthEditorProps) {
  const update = (patch: Partial<GroundTruthForm>) => onChange({ ...value, ...patch });
  const updateTool = (patch: Partial<GroundTruthTool>) => {
    update({ expectedTool: { ...value.expectedTool, ...patch } });
  };

  const preview = useMemo(() => {
    try {
      return serializeGroundTruth(value) || "{}";
    } catch {
      return "工具参数不是合法 JSON 对象";
    }
  }, [value]);

  return (
    <div className="space-y-4">
      <div className="grid gap-3 lg:grid-cols-2">
        <StringListEditor
          icon={<FileText className="h-4 w-4" />}
          label="参考文档 ID"
          metric="retrieval_doc_recall"
          placeholder="docId"
          values={value.referenceDocIds}
          onChange={(referenceDocIds) => update({ referenceDocIds })}
        />
        <StringListEditor
          icon={<Target className="h-4 w-4" />}
          label="预期意图叶子 ID"
          metric="intent_accuracy"
          placeholder="intentLeafId"
          values={value.expectedIntentLeafIds}
          onChange={(expectedIntentLeafIds) => update({ expectedIntentLeafIds })}
        />
        <StringListEditor
          icon={<BarChart3 className="h-4 w-4" />}
          label="必要事实"
          metric="answer_rule"
          placeholder="答案必须包含的关键词或事实"
          values={value.requiredFacts}
          onChange={(requiredFacts) => update({ requiredFacts })}
        />
        <StringListEditor
          icon={<X className="h-4 w-4" />}
          label="禁用断言"
          metric="answer_rule"
          placeholder="答案不能出现的错误说法"
          values={value.forbiddenClaims}
          onChange={(forbiddenClaims) => update({ forbiddenClaims })}
        />
      </div>

      <div className="grid gap-3 rounded-lg border border-slate-200 bg-white p-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
        <div className="space-y-2">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div className="flex items-center gap-2">
              <span className="rounded-md bg-slate-100 p-1.5 text-slate-500">
                <Wrench className="h-4 w-4" />
              </span>
              <label className="text-sm font-medium text-slate-900">预期 MCP 工具</label>
            </div>
            <Badge variant="outline" className="text-[11px]">mcp_tool_accuracy</Badge>
          </div>
          <Input
            value={value.expectedTool.name}
            onChange={(event) => updateTool({ name: event.target.value })}
            placeholder="weather_query"
          />
        </div>
        <div className="space-y-2">
          <div className="flex items-center justify-between gap-2">
            <label className="text-sm font-medium text-slate-900">工具参数 JSON</label>
            <Badge variant="secondary" className="text-[11px]">预留</Badge>
          </div>
          <Textarea
            value={value.expectedTool.argsText}
            onChange={(event) => updateTool({ argsText: event.target.value })}
            placeholder='{"city":"北京"}'
            className="min-h-[96px] font-mono text-xs"
          />
        </div>
      </div>

      <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
        <div className="space-y-2">
          <label className="text-sm font-medium">参考答案</label>
          <Textarea
            value={value.expectedAnswer}
            onChange={(event) => update({ expectedAnswer: event.target.value })}
            className="min-h-[118px]"
          />
        </div>
        <div className="space-y-2">
          <div className="flex items-center gap-2">
            <Code2 className="h-4 w-4 text-slate-500" />
            <label className="text-sm font-medium">JSON 预览</label>
          </div>
          <pre className="max-h-[160px] overflow-auto rounded-lg border border-slate-200 bg-slate-950 p-3 text-xs text-slate-100">
            {preview}
          </pre>
        </div>
      </div>
    </div>
  );
}

export function EvalPage() {
  const [suitePage, setSuitePage] = useState<PageResult<EvalSuite> | null>(null);
  const [casePage, setCasePage] = useState<PageResult<EvalCase> | null>(null);
  const [runPage, setRunPage] = useState<PageResult<EvalRun> | null>(null);
  const [selectedSuiteId, setSelectedSuiteId] = useState("");
  const [selectedRunDetail, setSelectedRunDetail] = useState<EvalRunDetail | null>(null);
  const [suiteLoading, setSuiteLoading] = useState(false);
  const [caseLoading, setCaseLoading] = useState(false);
  const [runLoading, setRunLoading] = useState(false);
  const [running, setRunning] = useState(false);
  const [suiteKeywordInput, setSuiteKeywordInput] = useState("");
  const [suiteKeyword, setSuiteKeyword] = useState("");
  const [caseKeywordInput, setCaseKeywordInput] = useState("");
  const [caseKeyword, setCaseKeyword] = useState("");
  const [suitePageNo, setSuitePageNo] = useState(1);
  const [casePageNo, setCasePageNo] = useState(1);
  const [runPageNo, setRunPageNo] = useState(1);
  const [casePageSize, setCasePageSize] = useState(PAGE_SIZE_OPTIONS[0]);
  const [runPageSize, setRunPageSize] = useState(PAGE_SIZE_OPTIONS[0]);
  const [trialCount, setTrialCount] = useState(3);
  const [suiteDialog, setSuiteDialog] = useState<SuiteDialogState>({ open: false, mode: "create", item: null });
  const [caseDialog, setCaseDialog] = useState<CaseDialogState>({ open: false, mode: "create", item: null });
  const [suiteForm, setSuiteForm] = useState(emptySuiteForm);
  const [caseForm, setCaseForm] = useState(createEmptyCaseForm);
  const [deleteSuiteTarget, setDeleteSuiteTarget] = useState<EvalSuite | null>(null);
  const [deleteCaseTarget, setDeleteCaseTarget] = useState<EvalCase | null>(null);
  const [deleteRunTarget, setDeleteRunTarget] = useState<EvalRun | null>(null);

  const suites = suitePage?.records || [];
  const cases = casePage?.records || [];
  const runs = runPage?.records || [];
  const selectedSuite = suites.find((item) => item.id === selectedSuiteId) || null;
  const currentReport = selectedRunDetail?.report;

  const stats = useMemo(() => {
    const latestRun = selectedRunDetail?.run || runs[0] || null;
    return [
      {
        key: "cases",
        label: "用例数",
        value: String(casePage?.total ?? 0),
        icon: <FileQuestion className="h-4 w-4" />
      },
      {
        key: "passAtK",
        label: "Pass@K",
        value: formatPercent(currentReport?.passAtK ?? latestRun?.passAtK),
        icon: <BarChart3 className="h-4 w-4" />
      },
      {
        key: "successRate",
        label: "Trial 成功率",
        value: formatPercent(currentReport?.successRate ?? latestRun?.successRate),
        icon: <BarChart3 className="h-4 w-4" />
      },
      {
        key: "duration",
        label: "最近耗时",
        value: formatDuration(latestRun?.durationMs),
        icon: <RefreshCw className="h-4 w-4" />
      }
    ];
  }, [casePage?.total, currentReport?.passAtK, currentReport?.successRate, runs, selectedRunDetail?.run]);

  const loadSuites = async (current = suitePageNo, keywordValue = suiteKeyword) => {
    setSuiteLoading(true);
    try {
      const data = await getEvalSuites({ current, size: 20, keyword: keywordValue || undefined });
      setSuitePage(data);
      if (!selectedSuiteId && data.records.length > 0) {
        setSelectedSuiteId(data.records[0].id);
      }
    } catch (error) {
      toast.error(getErrorMessage(error, "加载评测套件失败"));
    } finally {
      setSuiteLoading(false);
    }
  };

  const loadCases = async (current = casePageNo, keywordValue = caseKeyword) => {
    if (!selectedSuiteId) {
      setCasePage(null);
      return;
    }
    setCaseLoading(true);
    try {
      const data = await getEvalCases({
        current,
        size: casePageSize,
        suiteId: selectedSuiteId,
        keyword: keywordValue || undefined
      });
      setCasePage(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载评测用例失败"));
    } finally {
      setCaseLoading(false);
    }
  };

  const loadRuns = async (current = runPageNo) => {
    if (!selectedSuiteId) {
      setRunPage(null);
      return;
    }
    setRunLoading(true);
    try {
      const data = await getEvalRuns({
        current,
        size: runPageSize,
        suiteId: selectedSuiteId
      });
      setRunPage(data);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载评测运行失败"));
    } finally {
      setRunLoading(false);
    }
  };

  useEffect(() => {
    loadSuites();
  }, [suitePageNo, suiteKeyword]);

  useEffect(() => {
    setCasePageNo(1);
    setRunPageNo(1);
    setSelectedRunDetail(null);
  }, [selectedSuiteId]);

  useEffect(() => {
    loadCases();
  }, [selectedSuiteId, casePageNo, casePageSize, caseKeyword]);

  useEffect(() => {
    loadRuns();
  }, [selectedSuiteId, runPageNo, runPageSize]);

  useEffect(() => {
    if (!suiteDialog.open) {
      setSuiteForm(emptySuiteForm);
      return;
    }
    if (suiteDialog.mode === "edit" && suiteDialog.item) {
      setSuiteForm({
        name: suiteDialog.item.name || "",
        description: suiteDialog.item.description || "",
        version: suiteDialog.item.version || "v1"
      });
      return;
    }
    setSuiteForm(emptySuiteForm);
  }, [suiteDialog]);

  useEffect(() => {
    if (!caseDialog.open) {
      setCaseForm(createEmptyCaseForm());
      return;
    }
    if (caseDialog.mode === "edit" && caseDialog.item) {
      setCaseForm({
        question: caseDialog.item.question || "",
        category: caseDialog.item.category || "",
        groundTruth: parseGroundTruth(caseDialog.item.groundTruth || defaultGroundTruth),
        enabled: caseDialog.item.enabled ?? 1
      });
      return;
    }
    setCaseForm(createEmptyCaseForm());
  }, [caseDialog]);

  const handleSaveSuite = async () => {
    const payload = {
      name: suiteForm.name.trim(),
      description: suiteForm.description.trim() || null,
      version: suiteForm.version.trim() || "v1"
    };
    if (!payload.name) {
      toast.error("请输入套件名称");
      return;
    }
    try {
      if (suiteDialog.mode === "create") {
        const id = await createEvalSuite(payload);
        toast.success("套件已创建");
        setSelectedSuiteId(id);
      } else if (suiteDialog.item) {
        await updateEvalSuite(suiteDialog.item.id, payload);
        toast.success("套件已更新");
      }
      setSuiteDialog({ open: false, mode: "create", item: null });
      await loadSuites(1, suiteKeyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存套件失败"));
    }
  };

  const handleSaveCase = async () => {
    if (!selectedSuiteId) {
      toast.error("请选择评测套件");
      return;
    }
    const question = caseForm.question.trim();
    if (!question) {
      toast.error("请输入评测问题");
      return;
    }
    let groundTruth = "";
    try {
      groundTruth = serializeGroundTruth(caseForm.groundTruth);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "标准答案配置不合法");
      return;
    }
    try {
      if (caseDialog.mode === "create") {
        await createEvalCase({
          suiteId: selectedSuiteId,
          question,
          category: caseForm.category.trim() || null,
          groundTruth: groundTruth || null,
          enabled: caseForm.enabled
        });
        toast.success("用例已创建");
      } else if (caseDialog.item) {
        await updateEvalCase(caseDialog.item.id, {
          question,
          category: caseForm.category.trim() || null,
          groundTruth: groundTruth || null,
          enabled: caseForm.enabled
        });
        toast.success("用例已更新");
      }
      setCaseDialog({ open: false, mode: "create", item: null });
      await loadCases(1, caseKeyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "保存用例失败"));
    }
  };

  const handleRun = async () => {
    if (!selectedSuiteId) {
      toast.error("请选择评测套件");
      return;
    }
    try {
      setRunning(true);
      const detail = await runEvalSuite({ suiteId: selectedSuiteId, trialCount });
      setSelectedRunDetail(detail);
      toast.success("评测运行完成");
      await Promise.all([loadRuns(1), loadCases(casePageNo, caseKeyword)]);
    } catch (error) {
      toast.error(getErrorMessage(error, "运行评测失败"));
    } finally {
      setRunning(false);
    }
  };

  const handleOpenRun = async (run: EvalRun) => {
    try {
      setRunLoading(true);
      const detail = await getEvalRun(run.id);
      setSelectedRunDetail(detail);
    } catch (error) {
      toast.error(getErrorMessage(error, "加载运行详情失败"));
    } finally {
      setRunLoading(false);
    }
  };

  const handleDeleteSuite = async () => {
    if (!deleteSuiteTarget) return;
    try {
      await deleteEvalSuite(deleteSuiteTarget.id);
      toast.success("套件已删除");
      if (selectedSuiteId === deleteSuiteTarget.id) {
        setSelectedSuiteId("");
        setSelectedRunDetail(null);
      }
      await loadSuites(1, suiteKeyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除套件失败"));
    } finally {
      setDeleteSuiteTarget(null);
    }
  };

  const handleDeleteCase = async () => {
    if (!deleteCaseTarget) return;
    try {
      await deleteEvalCase(deleteCaseTarget.id);
      toast.success("用例已删除");
      await loadCases(1, caseKeyword);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除用例失败"));
    } finally {
      setDeleteCaseTarget(null);
    }
  };

  const handleDeleteRun = async () => {
    if (!deleteRunTarget) return;
    try {
      await deleteEvalRun(deleteRunTarget.id);
      toast.success("运行记录已删除");
      if (selectedRunDetail?.run.id === deleteRunTarget.id) {
        setSelectedRunDetail(null);
      }
      await loadRuns(runPageNo);
    } catch (error) {
      toast.error(getErrorMessage(error, "删除运行记录失败"));
    } finally {
      setDeleteRunTarget(null);
    }
  };

  return (
    <div className="admin-page">
      <div className="admin-page-header">
        <div>
          <h1 className="admin-page-title">评估中心</h1>
          <p className="admin-page-subtitle">评测集、Trial 运行、指标评分和 Trace 归因</p>
        </div>
        <div className="admin-page-actions">
          <Input
            value={suiteKeywordInput}
            onChange={(event) => setSuiteKeywordInput(event.target.value)}
            placeholder="搜索套件"
            className="w-[220px]"
          />
          <Button
            variant="outline"
            onClick={() => {
              setSuitePageNo(1);
              setSuiteKeyword(suiteKeywordInput.trim());
            }}
          >
            <Search className="mr-2 h-4 w-4" />
            搜索
          </Button>
          <Button variant="outline" onClick={() => loadSuites(suitePageNo, suiteKeyword)}>
            <RefreshCw className="mr-2 h-4 w-4" />
            刷新
          </Button>
          <Button className="admin-primary-gradient" onClick={() => setSuiteDialog({ open: true, mode: "create", item: null })}>
            <Plus className="mr-2 h-4 w-4" />
            新增套件
          </Button>
        </div>
      </div>

      <div className="grid gap-4 xl:grid-cols-[320px_minmax(0,1fr)]">
        <Card>
          <CardContent className="pt-5">
            <div className="mb-4 flex items-center justify-between">
              <div>
                <h2 className="text-base font-semibold text-slate-900">评测套件</h2>
                <p className="text-xs text-slate-500">共 {suitePage?.total ?? 0} 个</p>
              </div>
            </div>
            <div className="space-y-2">
              {suiteLoading ? (
                <div className="py-8 text-center text-sm text-slate-500">加载中...</div>
              ) : suites.length === 0 ? (
                <div className="py-8 text-center text-sm text-slate-500">暂无评测套件</div>
              ) : (
                suites.map((suite) => {
                  const active = suite.id === selectedSuiteId;
                  return (
                    <button
                      key={suite.id}
                      type="button"
                      onClick={() => setSelectedSuiteId(suite.id)}
                      className={`w-full rounded-lg border px-3 py-3 text-left transition ${
                        active
                          ? "border-indigo-200 bg-indigo-50 text-indigo-900"
                          : "border-slate-200 bg-white text-slate-700 hover:bg-slate-50"
                      }`}
                    >
                      <span className="flex items-center justify-between gap-2">
                        <span className="min-w-0 truncate text-sm font-semibold">{suite.name}</span>
                        <Badge variant={suite.enabled === 0 ? "secondary" : "default"}>
                          {suite.version || "v1"}
                        </Badge>
                      </span>
                      <span className="mt-1 block truncate text-xs text-slate-500">
                        {suite.description || "无描述"}
                      </span>
                    </button>
                  );
                })
              )}
            </div>
            {suitePage ? (
              <div className="mt-4 flex items-center justify-between text-xs text-slate-500">
                <Button
                  variant="outline"
                  size="sm"
                  disabled={suitePage.current <= 1}
                  onClick={() => setSuitePageNo((prev) => Math.max(1, prev - 1))}
                >
                  上一页
                </Button>
                <span>{suitePage.current} / {suitePage.pages || 1}</span>
                <Button
                  variant="outline"
                  size="sm"
                  disabled={suitePage.current >= suitePage.pages}
                  onClick={() => setSuitePageNo((prev) => prev + 1)}
                >
                  下一页
                </Button>
              </div>
            ) : null}
          </CardContent>
        </Card>

        <div className="space-y-4">
          <section className="grid gap-4 md:grid-cols-2 xl:grid-cols-4">
            {stats.map((stat) => (
              <div key={stat.key} className="rounded-lg border border-slate-200 bg-white p-4 shadow-sm">
                <div className="flex items-center justify-between">
                  <span className="text-xs text-slate-500">{stat.label}</span>
                  <span className="rounded-lg bg-slate-100 p-2 text-slate-500">{stat.icon}</span>
                </div>
                <p className="mt-2 text-2xl font-semibold text-slate-900">{stat.value}</p>
              </div>
            ))}
          </section>

          <Card>
            <CardContent className="pt-5">
              <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                <div>
                  <h2 className="text-base font-semibold text-slate-900">用例管理</h2>
                  <p className="text-xs text-slate-500">{selectedSuite?.name || "未选择套件"}</p>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <Input
                    value={caseKeywordInput}
                    onChange={(event) => setCaseKeywordInput(event.target.value)}
                    placeholder="搜索问题/分类"
                    className="w-[220px]"
                  />
                  <Button
                    variant="outline"
                    onClick={() => {
                      setCasePageNo(1);
                      setCaseKeyword(caseKeywordInput.trim());
                    }}
                  >
                    <Search className="mr-2 h-4 w-4" />
                    搜索
                  </Button>
                  <Button
                    className="admin-primary-gradient"
                    disabled={!selectedSuiteId}
                    onClick={() => setCaseDialog({ open: true, mode: "create", item: null })}
                  >
                    <Plus className="mr-2 h-4 w-4" />
                    新增用例
                  </Button>
                </div>
              </div>

              <div className="mt-4 overflow-x-auto rounded-lg border border-slate-200">
                <Table className="min-w-[1080px]">
                  <TableHeader>
                    <TableRow>
                      <TableHead>问题</TableHead>
                      <TableHead className="w-[120px]">分类</TableHead>
                      <TableHead className="w-[220px]">标准答案</TableHead>
                      <TableHead className="w-[120px]">状态</TableHead>
                      <TableHead className="w-[180px]">更新时间</TableHead>
                      <TableHead className="w-[170px] text-right">操作</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {caseLoading ? (
                      <TableRow>
                        <TableCell colSpan={6} className="py-8 text-center text-slate-500">加载中...</TableCell>
                      </TableRow>
                    ) : cases.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={6} className="py-8 text-center text-slate-500">暂无评测用例</TableCell>
                      </TableRow>
                    ) : (
                      cases.map((item) => (
                        <TableRow key={item.id}>
                          <TableCell className="max-w-[420px] truncate font-medium" title={item.question}>
                            {item.question}
                          </TableCell>
                          <TableCell>{item.category || "-"}</TableCell>
                          <TableCell>
                            <Badge variant={item.groundTruth ? "outline" : "secondary"} className="max-w-[200px] truncate">
                              {groundTruthSummary(item.groundTruth)}
                            </Badge>
                          </TableCell>
                          <TableCell>
                            <Badge variant={item.enabled === 0 ? "secondary" : "default"}>
                              {item.enabled === 0 ? "停用" : "启用"}
                            </Badge>
                          </TableCell>
                          <TableCell className="text-slate-500">
                            <RelativeTime value={item.updateTime || item.createTime} />
                          </TableCell>
                          <TableCell>
                            <div className="flex justify-end gap-2">
                              <Button variant="outline" size="sm" onClick={() => setCaseDialog({ open: true, mode: "edit", item })}>
                                <Pencil className="mr-1 h-4 w-4" />
                                编辑
                              </Button>
                              <Button variant="ghost" size="sm" className="text-destructive hover:text-destructive" onClick={() => setDeleteCaseTarget(item)}>
                                <Trash2 className="mr-1 h-4 w-4" />
                                删除
                              </Button>
                            </div>
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
              </div>

              {casePage ? (
                <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
                  <span>共 {casePage.total} 条</span>
                  <div className="flex items-center gap-2">
                    <PageSizeSelect
                      value={casePageSize}
                      onChange={(value) => {
                        setCasePageSize(value);
                        setCasePageNo(1);
                      }}
                    />
                    <Button variant="outline" size="sm" disabled={casePage.current <= 1} onClick={() => setCasePageNo((prev) => Math.max(1, prev - 1))}>
                      上一页
                    </Button>
                    <span>{casePage.current} / {casePage.pages || 1}</span>
                    <Button variant="outline" size="sm" disabled={casePage.current >= casePage.pages} onClick={() => setCasePageNo((prev) => prev + 1)}>
                      下一页
                    </Button>
                  </div>
                </div>
              ) : null}
            </CardContent>
          </Card>

          <Card>
            <CardContent className="pt-5">
              <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                <div>
                  <h2 className="text-base font-semibold text-slate-900">运行记录</h2>
                  <p className="text-xs text-slate-500">当前套件最近的评测运行</p>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <Input
                    type="number"
                    min={1}
                    max={20}
                    value={trialCount}
                    onChange={(event) => setTrialCount(Math.max(1, Number(event.target.value || 1)))}
                    className="w-[110px]"
                  />
                  <Button className="admin-primary-gradient" disabled={!selectedSuiteId || running} onClick={handleRun}>
                    <Play className="mr-2 h-4 w-4" />
                    {running ? "运行中..." : "运行评测"}
                  </Button>
                  <Button variant="outline" onClick={() => loadRuns(runPageNo)}>
                    <RefreshCw className="mr-2 h-4 w-4" />
                    刷新
                  </Button>
                </div>
              </div>

              <div className="mt-4 overflow-x-auto rounded-lg border border-slate-200">
                <Table className="min-w-[960px]">
                  <TableHeader>
                    <TableRow>
                      <TableHead>运行 ID</TableHead>
                      <TableHead className="w-[110px]">状态</TableHead>
                      <TableHead className="w-[110px]">Pass@K</TableHead>
                      <TableHead className="w-[130px]">Trial 成功率</TableHead>
                      <TableHead className="w-[130px]">进度</TableHead>
                      <TableHead className="w-[120px]">耗时</TableHead>
                      <TableHead className="w-[180px]">开始时间</TableHead>
                      <TableHead className="w-[180px] text-right">操作</TableHead>
                    </TableRow>
                  </TableHeader>
                  <TableBody>
                    {runLoading ? (
                      <TableRow>
                        <TableCell colSpan={8} className="py-8 text-center text-slate-500">加载中...</TableCell>
                      </TableRow>
                    ) : runs.length === 0 ? (
                      <TableRow>
                        <TableCell colSpan={8} className="py-8 text-center text-slate-500">暂无运行记录</TableCell>
                      </TableRow>
                    ) : (
                      runs.map((run) => (
                        <TableRow key={run.id}>
                          <TableCell className="font-mono text-xs">{run.id}</TableCell>
                          <TableCell>{statusBadge(run.status)}</TableCell>
                          <TableCell>{formatPercent(run.passAtK)}</TableCell>
                          <TableCell>{formatPercent(run.successRate)}</TableCell>
                          <TableCell>{run.completedTrials ?? 0} / {run.totalTrials ?? 0}</TableCell>
                          <TableCell>{formatDuration(run.durationMs)}</TableCell>
                          <TableCell className="text-slate-500">
                            <RelativeTime value={run.startedAt} />
                          </TableCell>
                          <TableCell>
                            <div className="flex justify-end gap-2">
                              <Button variant="outline" size="sm" onClick={() => handleOpenRun(run)}>
                                <Eye className="mr-1 h-4 w-4" />
                                详情
                              </Button>
                              <Button
                                variant="ghost"
                                size="sm"
                                className="text-destructive hover:text-destructive"
                                disabled={(run.status || "").toUpperCase() === "RUNNING"}
                                onClick={() => setDeleteRunTarget(run)}
                              >
                                <Trash2 className="mr-1 h-4 w-4" />
                                删除
                              </Button>
                            </div>
                          </TableCell>
                        </TableRow>
                      ))
                    )}
                  </TableBody>
                </Table>
              </div>

              {runPage ? (
                <div className="mt-4 flex flex-wrap items-center justify-between gap-2 text-sm text-slate-500">
                  <span>共 {runPage.total} 条</span>
                  <div className="flex items-center gap-2">
                    <PageSizeSelect
                      value={runPageSize}
                      onChange={(value) => {
                        setRunPageSize(value);
                        setRunPageNo(1);
                      }}
                    />
                    <Button variant="outline" size="sm" disabled={runPage.current <= 1} onClick={() => setRunPageNo((prev) => Math.max(1, prev - 1))}>
                      上一页
                    </Button>
                    <span>{runPage.current} / {runPage.pages || 1}</span>
                    <Button variant="outline" size="sm" disabled={runPage.current >= runPage.pages} onClick={() => setRunPageNo((prev) => prev + 1)}>
                      下一页
                    </Button>
                  </div>
                </div>
              ) : null}
            </CardContent>
          </Card>

          {selectedRunDetail ? (
            <Card>
              <CardContent className="pt-5">
                <div className="flex flex-col gap-3 lg:flex-row lg:items-center lg:justify-between">
                  <div>
                    <h2 className="text-base font-semibold text-slate-900">运行详情</h2>
                    <p className="font-mono text-xs text-slate-500">{selectedRunDetail.run.id}</p>
                  </div>
                  <div className="flex flex-wrap items-center gap-2">
                    {statusBadge(selectedRunDetail.run.status)}
                    <Badge variant="outline">Pass@K {formatPercent(selectedRunDetail.report?.passAtK)}</Badge>
                    <Badge variant="outline">成功率 {formatPercent(selectedRunDetail.report?.successRate)}</Badge>
                  </div>
                </div>

                <div className="mt-4 grid gap-3 md:grid-cols-2 xl:grid-cols-4">
                  {Object.entries(selectedRunDetail.report?.metricAverages || {}).map(([metric, value]) => (
                    <div key={metric} className="rounded-lg border border-slate-200 bg-slate-50 px-3 py-2">
                      <p className="text-xs text-slate-500">{metric}</p>
                      <p className="mt-1 text-lg font-semibold text-slate-900">{formatPercent(value)}</p>
                    </div>
                  ))}
                </div>

                <div className="mt-4 overflow-x-auto rounded-lg border border-slate-200">
                  <Table className="min-w-[1000px]">
                    <TableHeader>
                      <TableRow>
                        <TableHead className="w-[300px]">Case</TableHead>
                        <TableHead className="w-[90px]">Trial</TableHead>
                        <TableHead className="w-[100px]">状态</TableHead>
                        <TableHead className="w-[90px]">通过</TableHead>
                        <TableHead className="w-[110px]">耗时</TableHead>
                        <TableHead>评分</TableHead>
                        <TableHead className="w-[120px] text-right">Trace</TableHead>
                      </TableRow>
                    </TableHeader>
                    <TableBody>
                      {selectedRunDetail.trials.map((trial) => (
                        <TableRow key={trial.id}>
                          <TableCell className="max-w-[300px]">
                            <div className="truncate text-sm font-medium text-slate-900" title={trial.caseQuestion || trial.caseId}>
                              {trial.caseQuestion || trial.caseId}
                            </div>
                            <div className="mt-1 flex items-center gap-2">
                              {trial.caseCategory ? (
                                <Badge variant="outline" className="text-[11px]">
                                  {trial.caseCategory}
                                </Badge>
                              ) : null}
                              <span className="truncate font-mono text-[11px] text-slate-400">{trial.caseId}</span>
                            </div>
                          </TableCell>
                          <TableCell>{trial.trialIndex}</TableCell>
                          <TableCell>{statusBadge(trial.status)}</TableCell>
                          <TableCell>
                            <Badge variant={trial.success === 1 ? "default" : "secondary"}>
                              {trial.success === 1 ? "通过" : "未过"}
                            </Badge>
                          </TableCell>
                          <TableCell>{formatDuration(trial.latencyMs || trial.durationMs)}</TableCell>
                          <TableCell>
                            <div className="flex flex-wrap gap-1">
                              {(trial.scores || []).map((score) => (
                                <Badge key={score.id} variant={score.passed === 1 ? "default" : "secondary"}>
                                  {score.metricName}: {formatPercent(score.scoreValue)}
                                </Badge>
                              ))}
                            </div>
                          </TableCell>
                          <TableCell className="text-right">
                            {trial.traceId ? (
                              <Button asChild variant="outline" size="sm">
                                <Link to={`/admin/traces/${encodeURIComponent(trial.traceId)}`}>打开</Link>
                              </Button>
                            ) : (
                              "-"
                            )}
                          </TableCell>
                        </TableRow>
                      ))}
                    </TableBody>
                  </Table>
                </div>
              </CardContent>
            </Card>
          ) : null}
        </div>
      </div>

      <Dialog
        open={suiteDialog.open}
        onOpenChange={(open) => setSuiteDialog((prev) => ({ ...prev, open, item: open ? prev.item : null }))}
      >
        <DialogContent className="sm:max-w-[520px]">
          <DialogHeader>
            <DialogTitle>{suiteDialog.mode === "create" ? "新增评测套件" : "编辑评测套件"}</DialogTitle>
            <DialogDescription>维护评测套件名称和版本</DialogDescription>
          </DialogHeader>
          <div className="space-y-4">
            <div className="space-y-2">
              <label className="text-sm font-medium">名称</label>
              <Input value={suiteForm.name} onChange={(event) => setSuiteForm((prev) => ({ ...prev, name: event.target.value }))} />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">版本</label>
              <Input value={suiteForm.version} onChange={(event) => setSuiteForm((prev) => ({ ...prev, version: event.target.value }))} />
            </div>
            <div className="space-y-2">
              <label className="text-sm font-medium">描述</label>
              <Textarea value={suiteForm.description} onChange={(event) => setSuiteForm((prev) => ({ ...prev, description: event.target.value }))} />
            </div>
          </div>
          <DialogFooter>
            {suiteDialog.mode === "edit" && suiteDialog.item ? (
              <Button variant="ghost" className="mr-auto text-destructive hover:text-destructive" onClick={() => setDeleteSuiteTarget(suiteDialog.item)}>
                删除
              </Button>
            ) : null}
            <Button variant="outline" onClick={() => setSuiteDialog({ open: false, mode: "create", item: null })}>取消</Button>
            <Button onClick={handleSaveSuite}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <Dialog
        open={caseDialog.open}
        onOpenChange={(open) => setCaseDialog((prev) => ({ ...prev, open, item: open ? prev.item : null }))}
      >
        <DialogContent className="max-h-[88vh] overflow-y-auto sm:max-w-[980px]">
          <DialogHeader>
            <DialogTitle>{caseDialog.mode === "create" ? "新增评测用例" : "编辑评测用例"}</DialogTitle>
            <DialogDescription>配置问题、分类和用于自动评分的标准答案</DialogDescription>
          </DialogHeader>
          <div className="grid gap-4">
            <div className="space-y-2">
              <label className="text-sm font-medium">问题</label>
              <Textarea
                value={caseForm.question}
                onChange={(event) => setCaseForm((prev) => ({ ...prev, question: event.target.value }))}
                className="min-h-[90px]"
              />
            </div>
            <div className="grid gap-4 sm:grid-cols-[minmax(0,1fr)_160px]">
              <div className="space-y-2">
                <label className="text-sm font-medium">分类</label>
                <Input value={caseForm.category} onChange={(event) => setCaseForm((prev) => ({ ...prev, category: event.target.value }))} />
              </div>
              <div className="space-y-2">
                <label className="text-sm font-medium">启用</label>
                <select
                  value={caseForm.enabled}
                  onChange={(event) => setCaseForm((prev) => ({ ...prev, enabled: Number(event.target.value) }))}
                  className="h-10 w-full rounded-lg border border-slate-200 bg-white px-3 text-sm"
                >
                  <option value={1}>启用</option>
                  <option value={0}>停用</option>
                </select>
              </div>
            </div>
            <div className="space-y-2">
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <label className="text-sm font-medium">标准答案配置</label>
                  <p className="mt-1 text-xs text-slate-500">
                    这些字段会保存为 Ground Truth JSON，并驱动意图、检索、答案规则和工具命中评分。
                  </p>
                </div>
                <Badge variant="secondary">Ground Truth</Badge>
              </div>
              <GroundTruthEditor
                value={caseForm.groundTruth}
                onChange={(groundTruth) => setCaseForm((prev) => ({ ...prev, groundTruth }))}
              />
            </div>
          </div>
          <DialogFooter>
            <Button variant="outline" onClick={() => setCaseDialog({ open: false, mode: "create", item: null })}>取消</Button>
            <Button onClick={handleSaveCase}>保存</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      <AlertDialog open={Boolean(deleteSuiteTarget)} onOpenChange={(open) => !open && setDeleteSuiteTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除评测套件？</AlertDialogTitle>
            <AlertDialogDescription>删除后套件将不可见，相关用例和运行记录不会自动清理。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDeleteSuite} className="bg-destructive text-destructive-foreground">删除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={Boolean(deleteCaseTarget)} onOpenChange={(open) => !open && setDeleteCaseTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除评测用例？</AlertDialogTitle>
            <AlertDialogDescription>删除后该用例不会再参与后续评测运行。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDeleteCase} className="bg-destructive text-destructive-foreground">删除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>

      <AlertDialog open={Boolean(deleteRunTarget)} onOpenChange={(open) => !open && setDeleteRunTarget(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>删除运行记录？</AlertDialogTitle>
            <AlertDialogDescription>删除后这次运行的 trial 明细和评分记录也会一并删除。</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>取消</AlertDialogCancel>
            <AlertDialogAction onClick={handleDeleteRun} className="bg-destructive text-destructive-foreground">删除</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  );
}
