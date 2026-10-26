import { api } from './request'
import type { NovelCoverVO } from './cover'

export interface NovelVO {
  id: string
  title: string
  penName?: string
  genre?: string
  tags: string[]
  intro?: string
  status: string
  source: string
  inspirationSessionId?: string
  chapterCount: number
  wordCount: number
  /** 封面列表：主封面在前，书架卡片按此顺序左右切换 */
  covers?: NovelCoverVO[]
  createdAt?: string
  updatedAt?: string
}

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
  pages: number
}

export interface NovelQuery {
  page?: number
  size?: number
  keyword?: string
  status?: string
}

export function listNovels(params: NovelQuery = {}) {
  return api.get<PageResult<NovelVO>>('/novels', params)
}

export function getNovel(id: string) {
  return api.get<NovelVO>(`/novels/${id}`)
}
