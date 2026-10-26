import { api } from './request'

export type EntityType = 'CHARACTER' | 'ITEM' | 'FACTION' | 'CHARACTER_ATTRIBUTE' | 'INVENTORY'

export interface LocalSignal {
  name: string
  value: string
  /** HIGH 可疑 / MID 一般 / LOW 正常 */
  level: 'HIGH' | 'MID' | 'LOW' | string
}

export interface AiSignal {
  name: string
  detail: string
}

export interface NewCharacter {
  id: string
  name: string
  identity?: string
  rolePos?: string
  roleLabel?: string
  reason?: string
}

export interface ChangeRecord {
  id: string
  entityType: EntityType
  entityTypeLabel: string
  entityId: string
  entityName: string
  fieldKey: string
  fieldLabel: string
  beforeState?: string
  afterState?: string
  reason?: string
  evidence?: string
}

export interface EventChange {
  eventId: string
  title: string
  fromStatus: string
  toStatus: string
  fromLabel: string
  toLabel: string
  reason?: string
  changed: boolean
}

export interface ChapterAnalysisVO {
  id: string
  chapterId: string
  chapterNo: number
  chapterTitle: string
  wordCount: number
  targetWords: number
  minWords: number
  maxWords: number
  wordPass: boolean
  wordLabel: string
  wordComment: string
  outlineScore: number
  outlineVerdict: string
  outlineHits: string[]
  outlineMisses: string[]
  outlineSuggestions: string[]
  aiScore: number
  aiVerdict: string
  aiSignals: AiSignal[]
  aiExamples: string[]
  localSignals: LocalSignal[]
  newCharacters: NewCharacter[]
  changes: ChangeRecord[]
  events: EventChange[]
  warnings: string[]
  model?: string
  latencyMs: number
  createdAt?: string
}

export interface ChapterAnalysisSummaryVO {
  id: string
  chapterId: string
  chapterNo: number
  chapterTitle: string
  wordCount: number
  targetWords: number
  wordPass: boolean
  outlineScore: number
  aiScore: number
  model?: string
  createdAt?: string
}

export interface ChangeHistoryVO {
  id: string
  chapterId: string
  chapterNo: number
  chapterTitle?: string
  entityType: EntityType
  entityTypeLabel: string
  entityId: string
  entityName: string
  fieldKey: string
  fieldLabel: string
  beforeState?: string
  afterState?: string
  reason?: string
  evidence?: string
  createdAt?: string
}

/** 分析面板列表的一行：ANALYZED 已分析 / READY 待分析 / LOCKED 待前置 / EMPTY 正文为空 */
export type AnalysisRowStatus = 'ANALYZED' | 'READY' | 'LOCKED' | 'EMPTY'

export interface ChapterAnalysisOverviewVO {
  chapterId: string
  volumeNo: number
  volumeTitle?: string
  chapterNo: number
  title: string
  wordCount: number
  status: AnalysisRowStatus
  statusLabel: string
  analyzable: boolean
  blockReason?: string
  analysisId?: string
  outlineScore?: number
  aiScore?: number
  wordPass?: boolean
  wordLabel?: string
  targetWords?: number
  analysisCount?: number
  analyzedAt?: string
}

/** 章节分析：必须按章节顺序进行，服务端会校验前置章节 */
export function analyzeChapter(chapterId: string, targetWords?: number) {
  return api.post<ChapterAnalysisVO>(`/bookshelf/chapters/${chapterId}/analysis`, { targetWords }, 300000)
}

export function listAnalysisOverview(novelId: string) {
  return api.get<ChapterAnalysisOverviewVO[]>(`/bookshelf/novels/${novelId}/analysis-overview`)
}

export function listChapterAnalyses(chapterId: string) {
  return api.get<ChapterAnalysisSummaryVO[]>(`/bookshelf/chapters/${chapterId}/analyses`)
}

export function getAnalysis(id: string) {
  return api.get<ChapterAnalysisVO>(`/bookshelf/analyses/${id}`)
}

export function listChangeHistories(novelId: string, entityType?: EntityType, entityId?: string) {
  return api.get<ChangeHistoryVO[]>(`/bookshelf/novels/${novelId}/change-histories`, { entityType, entityId })
}

/* ---------------- 按分析修改 ---------------- */

/** 勾选的问题类型：大纲偏离 / 审稿建议 / AI 味信号 / AI 味原句 / 字数 */
export type ReviseItemType = 'OUTLINE_MISS' | 'OUTLINE_SUGGESTION' | 'AI_SIGNAL' | 'AI_EXAMPLE' | 'LENGTH'

export interface ReviseItem {
  type: ReviseItemType | string
  text: string
  detail?: string
}

/** 修改幅度：轻度只改点出的地方，重度允许重排场景 */
export type ReviseIntensity = 'LIGHT' | 'MEDIUM' | 'HEAVY'

export interface ChapterReviseRequest {
  analysisId?: string
  items: ReviseItem[]
  instruction?: string
  intensity?: ReviseIntensity
  targetWords?: number
}

export interface ChapterReviseVO {
  chapterId: string
  chapterNo: number
  chapterTitle: string
  originalContent: string
  revisedContent: string
  notes: string[]
  wordCountBefore: number
  wordCountAfter: number
  intensity: ReviseIntensity
  model?: string
  latencyMs: number
}

/** 按分析修改：只生成不落库，作者在对比窗口确认后再保存 */
export function reviseChapter(chapterId: string, payload: ChapterReviseRequest) {
  return api.post<ChapterReviseVO>(`/bookshelf/chapters/${chapterId}/revise`, payload, 600000)
}
