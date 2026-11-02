import { api } from './request'

/** 伏笔状态：未回收 / 已回收 / 作者放弃 */
export type ForeshadowStatus = 'OPEN' | 'PAID' | 'DROPPED'

export interface ForeshadowVO {
  id: string
  title: string
  content?: string
  plantedChapterNo?: number
  plannedPayoffChapter?: number
  status: ForeshadowStatus
  statusLabel: string
  payoffChapterNo?: number
  /** 是否已超期（服务端按当前写作章实时算） */
  overdue: boolean
  overdueChapters: number
  note?: string
}

export interface CharacterStateVO {
  characterId: string
  characterName: string
  chapterNo: number
  location?: string
  condition?: string
  mood?: string
  knowsAdded: string[]
  knowsHidden: string[]
}

/** 章节事实层：这一章实际发生了什么 */
export interface ChapterFactsVO {
  chapterId: string
  chapterNo: number
  chapterTitle: string
  factSummary?: string
  /** AI / MANUAL / BACKFILL；为空表示还没分析出事实 */
  factSource?: string
  planted: ForeshadowVO[]
  paid: ForeshadowVO[]
  states: CharacterStateVO[]
}

export interface ForeshadowRequest {
  title?: string
  content?: string
  plantedChapterNo?: number
  plannedPayoffChapter?: number
  status?: ForeshadowStatus
  note?: string
}

export function getChapterFacts(chapterId: string) {
  return api.get<ChapterFactsVO>(`/bookshelf/chapters/${chapterId}/facts`)
}

/** 手工编辑事实摘要：来源置 MANUAL，之后重新分析不会覆盖 */
export function updateFactSummary(chapterId: string, factSummary: string) {
  return api.put<ChapterFactsVO>(`/bookshelf/chapters/${chapterId}/fact-summary`, { factSummary })
}

export function listForeshadows(novelId: string, status?: ForeshadowStatus) {
  return api.get<ForeshadowVO[]>(`/bookshelf/novels/${novelId}/foreshadows`, status ? { status } : undefined)
}

export function createForeshadow(novelId: string, body: ForeshadowRequest) {
  return api.post<ForeshadowVO>(`/bookshelf/novels/${novelId}/foreshadows`, body)
}

export function updateForeshadow(id: string, body: ForeshadowRequest) {
  return api.put<ForeshadowVO>(`/bookshelf/foreshadows/${id}`, body)
}

export function deleteForeshadow(id: string) {
  return api.delete<void>(`/bookshelf/foreshadows/${id}`)
}
