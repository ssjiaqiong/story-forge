import { api, request } from './request'

/** 逆推摘要：这次从原文里还原出多少资产 */
export interface ImportSummary {
  characters?: number
  relations?: number
  factions?: number
  items?: number
  settings?: number
  masterOutlines?: number
  chapterOutlines?: number
  readSegments?: number
  sampled?: boolean
  model?: string
  latencyMs?: number
  notes?: string[]
}

/** 整本导入任务 */
export interface ImportTaskVO {
  taskId: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  percent: number
  stepNo: number
  stepTotal: number
  stepLabel?: string
  errorMessage?: string
  sourceName?: string
  title?: string
  penName?: string
  genre?: string
  sourceChars?: number
  chapterCount?: number
  importedChapters?: number
  novelId?: string
  createdAt?: string
  summary?: ImportSummary
}

/** 导入记录列表项 */
export interface ImportRecordVO {
  taskId: string
  sourceName?: string
  title?: string
  sourceChars?: number
  chapterCount?: number
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  novelId?: string
  createdAt?: string
  errorMessage?: string
}

export interface ImportStartPayload {
  file: File
  title?: string
  penName?: string
}

/** 上传整本 TXT 并开始导入（原文会完整落库） */
export function startImport(payload: ImportStartPayload): Promise<ImportTaskVO> {
  const form = new FormData()
  form.append('file', payload.file)
  if (payload.title) {
    form.append('title', payload.title)
  }
  if (payload.penName) {
    form.append('penName', payload.penName)
  }
  return request<ImportTaskVO>({
    url: '/bookshelf/import/tasks',
    method: 'POST',
    data: form,
    timeout: 180000
  })
}

/** 查询导入进度 */
export function getImportTask(taskId: string): Promise<ImportTaskVO> {
  return api.get<ImportTaskVO>(`/bookshelf/import/tasks/${taskId}`)
}

/** 正文已入库但分析失败时，只重跑 AI 逆推 */
export function retryImport(taskId: string): Promise<ImportTaskVO> {
  return api.post<ImportTaskVO>(`/bookshelf/import/tasks/${taskId}/retry`, undefined, 60000)
}

/** 历史导入记录 */
export function listImports(page = 1, size = 5) {
  return api.get<{ records: ImportRecordVO[]; total: number }>('/bookshelf/import/tasks', { page, size })
}

/** 删除导入记录（已生成的书保留） */
export function deleteImport(taskId: string): Promise<void> {
  return api.delete<void>(`/bookshelf/import/tasks/${taskId}`)
}
