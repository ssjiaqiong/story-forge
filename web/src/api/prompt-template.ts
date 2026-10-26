import { api, http } from './request'

/** 可用变量：写模板时用 {{name}} 占位 */
export interface PromptVariableVO {
  name: string
  label: string
  sample: string
}

/** 模板列表项 */
export interface PromptTemplateItemVO {
  scene: string
  displayName: string
  group: string
  /** 是否开放编辑；false 表示该场景仍使用系统内置提示词 */
  editable: boolean
  /** 是否已被用户改写 */
  overridden: boolean
  variableCount: number
  updatedAt?: string
}

/** 模板详情 */
export interface PromptTemplateDetailVO extends PromptTemplateItemVO {
  /** 当前生效的指令段（无自定义时等于 defaultContent） */
  content: string
  /** 系统默认指令段 */
  defaultContent: string
  /** 锁定的输出格式契约，只读 */
  outputContract: string
  variables: PromptVariableVO[]
  note?: string
}

/** 预览结果：渲染后的用户段 + 锁定的输出格式段 + 拼装后的完整提示词 */
export interface PromptPreviewVO {
  scene: string
  content: string
  outputContract: string
  full: string
}

export function listPromptTemplates() {
  return api.get<PromptTemplateItemVO[]>('/prompt-templates')
}

export function getPromptTemplate(scene: string) {
  return api.get<PromptTemplateDetailVO>(`/prompt-templates/${scene}`)
}

export function savePromptTemplate(scene: string, content: string) {
  return api.put<PromptTemplateDetailVO>(`/prompt-templates/${scene}`, { content })
}

export function resetPromptTemplate(scene: string) {
  return api.delete<void>(`/prompt-templates/${scene}`)
}

export function previewPromptTemplate(scene: string) {
  return api.post<PromptPreviewVO>(`/prompt-templates/${scene}/preview`, {}, 60000)
}

/* ---------------- 导入 / 导出 ---------------- */

/** 包内一条场景 */
export interface PromptExportItemVO {
  scene: string
  displayName: string
  group: string
  content: string
}

/** 导出/导入的包结构（导入时原样回传） */
export interface PromptExportPackageVO {
  format: string
  formatVersion: number
  exportedAt?: string
  site?: string
  onlyOverridden?: boolean
  note?: string
  total?: number
  scenes: PromptExportItemVO[]
}

/** 单条导入结果 */
export interface PromptImportItemResult {
  scene: string
  displayName: string
  status: 'IMPORTED' | 'SKIPPED'
  message?: string
}

/** 导入结果 */
export interface PromptImportResultVO {
  mode: string
  total: number
  imported: number
  skipped: number
  items: PromptImportItemResult[]
}

/** 导入模式 */
export type PromptImportMode = 'MERGE' | 'SKIP_EXISTING' | 'REPLACE'

/** 导出提示词模板包（json 文件流） */
export async function exportPromptTemplates(onlyOverridden = true): Promise<Blob> {
  const response = await http.get<Blob>('/prompt-templates/export', {
    params: { onlyOverridden },
    responseType: 'blob'
  })
  return response.data
}

/** 导入提示词模板包 */
export function importPromptTemplates(pkg: unknown, mode: PromptImportMode = 'MERGE') {
  return api.post<PromptImportResultVO>(
    `/prompt-templates/import?mode=${encodeURIComponent(mode)}`, pkg, 120000)
}

/** 分组排序：常用场景优先，其余按出现顺序兜底 */
export const PROMPT_GROUP_ORDER = [
  '正文',
  '大纲',
  '蓝图',
  '角色与设定',
  '事件',
  '分析与质检',
  '封面',
  '创作助手'
]
