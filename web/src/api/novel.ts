import { api } from './request'
import type { NovelCoverVO } from './cover'

export interface NovelVO {
  id: string
  title: string
  penName?: string
  genre?: string
  tags: string[]
  intro?: string
  /** 创作约束：注入此后所有 AI 生成的硬要求 */
  constraintText?: string
  /** 预计完结字数；0 / 空 表示未设置（此时不可选「直到完结」的自动写书模式） */
  plannedWords?: number
  /** 本书是否处于自动生成中（自动写书运行态）；为 true 时前端禁用全部编辑 */
  autoWriting?: boolean
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
