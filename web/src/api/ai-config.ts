import api, { http } from '@/api/request'

/** AI 接口配置（出参） */
export interface AiConfigVO {
  id: string
  name: string
  /** TEXT 文本接口 / IMAGE 图像（文生图） */
  kind: 'TEXT' | 'IMAGE'
  /** 平台协议（文生图），文本接口固定 OPENAI */
  provider: string
  /** 图像接口默认出图尺寸 */
  imageSize?: string
  baseUrl: string
  apiKeyMask?: string
  /** 明文密钥，仅详情接口返回（编辑页回填用） */
  apiKey?: string
  apiKeyConfigured: boolean
  defaultModel: string
  modelList: string[]
  timeoutMs: number
  temperature: number
  maxTokens: number
  topP: number
  extraHeaders?: string
  enabled: boolean
  isDefault: boolean
  remark?: string
  lastTestAt?: string
  lastTestOk?: boolean
  lastTestLatencyMs?: number
  createdAt?: string
  updatedAt?: string
}

/** 新增 / 编辑入参 */
export interface AiConfigPayload {
  name?: string
  /** 仅新增时有效：接口类型创建后不可变更 */
  kind?: 'TEXT' | 'IMAGE'
  provider?: string
  imageSize?: string
  baseUrl?: string
  /** 编辑时留空表示保留原密钥 */
  apiKey?: string
  defaultModel?: string
  timeoutMs?: number
  temperature?: number
  maxTokens?: number
  topP?: number
  extraHeaders?: string
  enabled?: boolean
  isDefault?: boolean
  remark?: string
}

/** 文生图平台预设 */
export interface ImageProviderPresetVO {
  provider: string
  label: string
  baseUrl: string
  model: string
  imageSize: string
  hint: string
  supportsNegativePrompt: boolean
  supportsModelList: boolean
}

/** 连通性测试结果 */
export interface AiTestResult {
  ok: boolean
  latencyMs: number
  model?: string
  reply?: string
  totalTokens?: number
  error?: string
}

/** 试拉模型列表入参（表单未保存也可用） */
export interface AiModelProbePayload {
  id?: string
  kind?: 'TEXT' | 'IMAGE'
  provider?: string
  baseUrl: string
  apiKey?: string
  extraHeaders?: string
  timeoutMs?: number
}

/** 场景绑定视图 */
export interface SceneBindingVO {
  code: string
  label: string
  group: string
  /** 该场景需要的接口类型，界面据此过滤可选项 */
  kind: 'TEXT' | 'IMAGE'
  aiConfigId?: string
  aiConfigName?: string
  model?: string
  temperature?: number
  maxTokens?: number
}

/** 场景绑定提交项 */
export interface SceneBindingItem {
  code: string
  aiConfigId?: string | null
  model?: string | null
  temperature?: number | null
  maxTokens?: number | null
}

export function listAiConfigs() {
  return api.get<AiConfigVO[]>('/ai-configs')
}

/** 文生图平台预设（地址 / 模型 / 尺寸建议） */
export function listImageProviderPresets() {
  return api.get<ImageProviderPresetVO[]>('/ai-configs/presets')
}

export function getAiConfig(id: string) {
  return api.get<AiConfigVO>(`/ai-configs/${id}`)
}

export function createAiConfig(payload: AiConfigPayload) {
  return api.post<AiConfigVO>('/ai-configs', payload)
}

export function updateAiConfig(id: string, payload: AiConfigPayload) {
  return api.put<AiConfigVO>(`/ai-configs/${id}`, payload)
}

export function deleteAiConfig(id: string) {
  return api.delete<void>(`/ai-configs/${id}`)
}

export function setDefaultAiConfig(id: string) {
  return api.put<AiConfigVO>(`/ai-configs/${id}/default`)
}

export function changeAiConfigEnabled(id: string, enabled: boolean) {
  return api.put<AiConfigVO>(`/ai-configs/${id}/status`, { enabled })
}

export function testAiConfig(id: string) {
  return api.post<AiTestResult>(`/ai-configs/${id}/test`)
}

export function fetchAiConfigModels(id: string) {
  return api.get<string[]>(`/ai-configs/${id}/models`)
}

export function probeAiModels(payload: AiModelProbePayload) {
  return api.post<string[]>('/ai-configs/models/probe', payload)
}

export function listSceneBindings() {
  return api.get<SceneBindingVO[]>('/scene-bindings')
}

export function saveSceneBindings(items: SceneBindingItem[]) {
  return api.put<void>('/scene-bindings', { items })
}

/* ---------- 导入 / 导出 ---------- */

/** 导出包里的单条接口配置 */
export interface AiConfigExportItem {
  name: string
  kind: 'TEXT' | 'IMAGE'
  provider?: string
  imageSize?: string
  baseUrl: string
  /** 明文密钥；导出时选择不含密钥则为空 */
  apiKey?: string
  apiKeyMask?: string
  defaultModel: string
  modelList?: string[]
  timeoutMs?: number
  temperature?: number
  maxTokens?: number
  topP?: number
  extraHeaders?: string
  enabled?: boolean
  isDefault?: boolean
  remark?: string
}

/** 导出包（导入的入参也是它） */
export interface AiConfigExportPackage {
  format?: string
  formatVersion?: number
  exportedAt?: string
  total?: number
  /** 文件里是否真的含 API Key 明文 */
  containsApiKey?: boolean
  site?: string
  configs: AiConfigExportItem[]
}

/** 导入结果 */
export interface AiConfigImportResult {
  total: number
  imported: number
  skipped: number
  withApiKey: number
  /** 重命名明细，如「OpenAI → OpenAI（2）」 */
  renamed: string[]
  /** 跳过明细，如「测试接口：接口地址不能为空」 */
  skippedItems: string[]
  configs: AiConfigVO[]
}

/**
 * 导出全部接口配置（JSON 文件流）。
 *
 * @param includeApiKey 是否包含 API Key 明文；包含时文件等同于凭据，需提示用户保管
 */
export async function exportAiConfigs(includeApiKey = true): Promise<Blob> {
  const response = await http.get<Blob>('/ai-configs/export', {
    params: { includeApiKey },
    responseType: 'blob'
  })
  return response.data
}

/** 导入接口配置 JSON，返回导入统计与导入后的列表 */
export function importAiConfigs(pkg: unknown) {
  return api.post<AiConfigImportResult>('/ai-configs/import', pkg, 120000)
}
