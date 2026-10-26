import api from '@/api/request'

export interface PageResult<T> {
  records: T[]
  total: number
  page: number
  size: number
  pages: number
}

/** 运营三态：创作中 / 完结 / 废弃 */
export type NovelState = 'CREATING' | 'COMPLETED' | 'ABANDONED'

/** 数据范围：正常 / 已逻辑删除 / 全部 */
export type NovelScope = 'NORMAL' | 'DELETED' | 'ALL'

export interface AdminNovelVO {
  id: string
  title: string
  penName?: string
  genre?: string
  intro?: string
  /** 库内状态：DRAFT / WRITING / FINISHED / ARCHIVED */
  status: string
  state: NovelState
  /** 0 正常 / 1 已逻辑删除（仅超管可见） */
  deleted: number
  userId: string
  username: string
  nickname?: string
  chapterCount: number
  wordCount: number
  createdAt: string
  updatedAt: string
}

export interface AdminChapterVO {
  id: string
  novelId: string
  volumeNo: number
  volumeTitle?: string
  chapterNo: number
  title: string
  summary?: string
  /** 仅单章详情接口返回 */
  content?: string
  wordCount: number
  status: string
  /** 章节自身的逻辑删除标记（小说被删除时章节一并删除） */
  deleted?: number
  createdAt?: string
  updatedAt?: string
}

export interface AdminNovelDetailVO {
  novel: AdminNovelVO
  chapters: AdminChapterVO[]
}

export interface AdminNovelQuery {
  page?: number
  size?: number
  keyword?: string
  state?: NovelState | ''
  scope?: NovelScope
  createdFrom?: string
  createdTo?: string
  sort?: string
}

export interface AdminPurgeResultVO {
  novelId: string
  title?: string
  username?: string
  success: boolean
  message?: string
  /** 各关联表删除行数，键为表名 */
  tableRows: Record<string, number>
  totalRows: number
  coversRemoved: boolean
}

export function pageAdminNovels(query: AdminNovelQuery) {
  const params: Record<string, unknown> = {
    page: query.page ?? 1,
    size: query.size ?? 20,
    sort: query.sort || 'CREATED_DESC',
    scope: query.scope || 'NORMAL'
  }
  if (query.keyword) params.keyword = query.keyword
  if (query.state) params.state = query.state
  if (query.createdFrom) params.createdFrom = query.createdFrom
  if (query.createdTo) params.createdTo = query.createdTo
  return api.get<PageResult<AdminNovelVO>>('/admin/novels', params)
}

export function getAdminNovel(id: string) {
  return api.get<AdminNovelDetailVO>(`/admin/novels/${id}`)
}

export function getAdminChapter(novelId: string, chapterId: string) {
  return api.get<AdminChapterVO>(`/admin/novels/${novelId}/chapters/${chapterId}`)
}

/** 物理清除单本已逻辑删除的小说（含全部关联数据与封面文件，不可恢复）。 */
export function purgeAdminNovel(id: string) {
  return api.delete<AdminPurgeResultVO>(`/admin/novels/${id}`)
}

/** 批量物理清除，单次最多 50 本。 */
export function purgeAdminNovels(ids: string[]) {
  return api.post<AdminPurgeResultVO[]>('/admin/novels/purge', { ids })
}
