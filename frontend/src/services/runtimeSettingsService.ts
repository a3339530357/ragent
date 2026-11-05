import { api } from "./api";

export type ConfigValue = string | number | boolean | null | ConfigValue[] | ConfigObject;
export interface ConfigObject {
  [key: string]: ConfigValue;
}
export interface RuntimeInstance {
  instanceId: string;
  appliedVersion: number;
  targetVersion: number;
  error?: string | null;
}
export interface RuntimeSettings {
  activeVersion: number;
  appliedVersion: number;
  draftRevision: number;
  draftBaseVersion: number;
  writable: boolean;
  applyError?: string | null;
  effective: ConfigObject;
  draft: ConfigObject | null;
  instances: RuntimeInstance[];
  changes: string[];
}
export interface ConfigVersion {
  id: number;
  createdBy: string;
  createTime: string;
  rollbackFrom?: number | null;
}
export interface ConfigRevision {
  draftRevision: number;
  baseVersion: number;
}
const base = "/rag/settings/runtime";
export const loadRuntimeSettings = () => api.get<RuntimeSettings, RuntimeSettings>(base);
export const saveRuntimeDraft = (revision: ConfigRevision, config: ConfigObject) =>
  api.put<number, number>(`${base}/draft`, { ...revision, config });
export const validateRuntimeDraft = (revision: ConfigRevision) =>
  api.post<string[], string[]>(`${base}/validate`, revision);
export const publishRuntimeDraft = (revision: ConfigRevision) =>
  api.post<number, number>(`${base}/publish`, revision);
export const rollbackRuntimeConfig = (revision: ConfigRevision, version: number) =>
  api.post<number, number>(`${base}/rollback`, { ...revision, version });
export const loadRuntimeVersions = () =>
  api.get<ConfigVersion[], ConfigVersion[]>(`${base}/versions`);
export const loadRuntimeVersion = (version: number) =>
  api.get<ConfigObject, ConfigObject>(`${base}/versions/${version}`);
export const testRuntimeModel = (revision: ConfigRevision, capability: string, modelId: string) =>
  api.post<void, void>(`${base}/test`, { ...revision, capability, modelId });
