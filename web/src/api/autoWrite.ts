import { api } from './request'

/** 完结口径：写到指定章数 / 直到写完预计字数 */
export type AutoWriteEndMode = 'CHAPTERS' | 'UNTIL_END'

/** 运行态 */
export type AutoWriteStatus = 'IDLE' | 'RUNNING' | 'PAUSED' | 'STOPPED' | 'COMPLETED'

export interface AutoWriteTask {
  id: string
  novelId: string
  enabled: boolean
  cronExpr: string
  chaptersPerRun: number
  endMode: AutoWriteEndMode
  endChapterNo?: number | null
  hardIssueLimit: number
  consistencyAi: boolean
  targetWordsPerChapter: number
  status: AutoWriteStatus
  /** 本书是否处于自动化生成状态（= 编辑已锁） */
  autoWriting: boolean
  closingTriggered: boolean
  consecutiveFailures: number
  totalRuns: number
  generatedChapters: number
  lastRunAt?: string | null
  lastResult?: string | null
  stoppedReason?: string | null
  nextRunAt?: string | null
}

export interface AutoWriteTaskRequest {
  enabled?: boolean
  cronExpr: string
  chaptersPerRun: number
  endMode: AutoWriteEndMode
  endChapterNo?: number | null
  hardIssueLimit: number
  consistencyAi: boolean
  targetWordsPerChapter: number
}

export interface AutoWriteRun {
  id: string
  taskId: string
  status: 'SUCCEEDED' | 'FAILED' | 'SKIPPED' | string
  startedAt?: string | null
  finishedAt?: string | null
  chaptersWritten: number
  outlinesCreated: number
  masterOutlinesCreated: number
  consistencyHard?: number | null
  message?: string | null
}

export interface AutoWriteStatusVO {
  exists: boolean
  autoWriting: boolean
  status?: AutoWriteStatus | null
  enabled?: boolean | null
  generatedChapters?: number | null
  nextRunAt?: string | null
}

const BASE = '/auto-write'

export const autoWriteApi = {
  /** 取某本书的定时任务（无则返回 null） */
  getByNovel: (novelId: string) => api.get<AutoWriteTask | null>(`${BASE}/novels/${novelId}`),
  /** 保存（新建或更新）某本书的定时任务 */
  save: (novelId: string, data: AutoWriteTaskRequest) =>
    api.post<AutoWriteTask>(`${BASE}/novels/${novelId}`, data),
  /** 启用 / 停用（后端为 @RequestParam，必须走查询串） */
  setEnabled: (id: string, enabled: boolean) =>
    api.put<AutoWriteTask>(`${BASE}/${id}/enabled?enabled=${enabled}`, undefined, 30000),
  /** 立即执行一轮 */
  runNow: (id: string) => api.post<{ accepted: boolean }>(`${BASE}/${id}/run-now`, undefined, 30000),
  /** 删除任务 */
  remove: (id: string) => api.delete<void>(`${BASE}/${id}`),
  /** 执行流水（最近 50 轮） */
  runs: (id: string) => api.get<AutoWriteRun[]>(`${BASE}/${id}/runs`),
  /** 轻量状态（供横幅 / 轮询） */
  status: (novelId: string) => api.get<AutoWriteStatusVO>(`${BASE}/novels/${novelId}/status`)
}
