import { api, request } from './request'
import type { CharacterDraft } from './bookshelf'

/** 分幕结构 */
export interface DeconstructAct {
  name?: string
  range?: string
  content?: string
}

/** 人物功能位 */
export interface DeconstructFunction {
  function?: string
  originalRole?: string
  role?: string
}

/** 主线脉络 */
export interface DeconstructSpine {
  logline?: string
  theme?: string
  mainline?: string
  acts: DeconstructAct[]
  turns: string[]
  characterFunctions: DeconstructFunction[]
  ending?: string
}

export interface DeconstructRelation {
  from?: string
  to?: string
  type?: string
  description?: string
}

export interface DeconstructMember {
  character?: string
  position?: string
}

export interface DeconstructFaction {
  name?: string
  category?: string
  status?: string
  scope?: string
  remark?: string
  members: DeconstructMember[]
}

export interface DeconstructChapter {
  volumeNo?: number
  volumeTitle?: string
  chapterNo?: number
  title?: string
  summary?: string
  characters: string[]
  events: string[]
}

export interface DeconstructEvent {
  title?: string
  eventTime?: string
  description?: string
  status?: string
  participants: string[]
}

/** 拆书结果 */
export interface DeconstructResult {
  title?: string
  genre?: string
  tags: string[]
  synopsis?: string
  worldview?: string
  background?: string
  coreConflict?: string
  highlights: string[]
  style?: string
  spine?: DeconstructSpine
  protagonist?: CharacterDraft
  characters: CharacterDraft[]
  relations: DeconstructRelation[]
  factions: DeconstructFaction[]
  chapters: DeconstructChapter[]
  events: DeconstructEvent[]
  adaptationLevel?: string
  adaptationLabel?: string
  backgroundDirection?: string
  sourceChars?: number
  sourceChapters?: number
  sampled?: boolean
  model?: string
  latencyMs?: number
}

/** 拆书任务 */
export interface DeconstructTaskVO {
  taskId: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  percent: number
  stepNo: number
  stepTotal: number
  stepLabel?: string
  message?: string
  errorMessage?: string
  sourceName?: string
  sourceChars?: number
  sourceChapters?: number
  adaptationLevel?: string
  backgroundDirection?: string
  chapterTarget?: number
  result?: DeconstructResult
  generatedNovelId?: string
}

/** 拆书记录列表项 */
export interface DeconstructSummaryVO {
  taskId: string
  sourceName?: string
  sourceChars?: number
  adaptationLevel?: string
  adaptationLabel?: string
  chapterTarget?: number
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  title?: string
  generatedNovelId?: string
  createdAt?: string
}

export interface DeconstructStartPayload {
  file: File
  adaptationLevel: string
  backgroundDirection?: string
  chapterTarget?: number
}

/** 上传小说并开始拆书（原文不落库，只保留文件名与字数） */
export function startDeconstruct(payload: DeconstructStartPayload): Promise<DeconstructTaskVO> {
  const form = new FormData()
  form.append('file', payload.file)
  form.append('adaptationLevel', payload.adaptationLevel)
  if (payload.backgroundDirection) {
    form.append('backgroundDirection', payload.backgroundDirection)
  }
  if (payload.chapterTarget) {
    form.append('chapterTarget', String(payload.chapterTarget))
  }
  return request<DeconstructTaskVO>({
    url: '/bookshelf/deconstruct/tasks',
    method: 'POST',
    data: form,
    timeout: 120000
  })
}

/** 查询拆书进度与结果 */
export function getDeconstructTask(taskId: string): Promise<DeconstructTaskVO> {
  return api.get<DeconstructTaskVO>(`/bookshelf/deconstruct/tasks/${taskId}`)
}

/** 历史拆书记录 */
export function listDeconstruct(page = 1, size = 6) {
  return api.get<{ records: DeconstructSummaryVO[]; total: number }>('/bookshelf/deconstruct/tasks', { page, size })
}

/** 保存为新小说；返回小说 ID（雪花 ID 以字符串下发） */
export function saveDeconstruct(taskId: string): Promise<string> {
  return api.post<string>(`/bookshelf/deconstruct/tasks/${taskId}/save`, undefined, 120000)
}

/** 删除拆书记录 */
export function deleteDeconstruct(taskId: string): Promise<void> {
  return api.delete<void>(`/bookshelf/deconstruct/tasks/${taskId}`)
}
