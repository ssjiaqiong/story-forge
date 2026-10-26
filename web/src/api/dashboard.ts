import { api } from './request'

/** 账号与登录信息 */
export interface DashboardProfileVO {
  username: string
  nickname?: string
  roleLabel: string
  registeredAt?: string
  usedDays: number
  /** 本次登录时间 */
  lastLoginAt?: string
  lastLoginIp?: string
  /** 上一次登录时间，首次登录为空 */
  prevLoginAt?: string
  prevLoginIp?: string
}

/** 小说维度统计 */
export interface DashboardNovelStatsVO {
  total: number
  draft: number
  /** 连载中 */
  writing: number
  /** 已完结 */
  finished: number
  archived: number
  wordCount: number
  chapterCount: number
  avgWordsPerChapter: number
}

/** 章节维度统计 */
export interface DashboardChapterStatsVO {
  total: number
  done: number
  writing: number
  draft: number
}

/** AI 用量统计 */
export interface DashboardAiStatsVO {
  todayTokens: number
  todayCalls: number
  todayPromptTokens: number
  todayCompletionTokens: number
  todayFailed: number
  monthTokens: number
  monthCalls: number
  totalTokens: number
  totalCalls: number
  lastCalledAt?: string
}

/** 趋势点（一天） */
export interface DashboardTrendPointVO {
  day: string
  label: string
  words: number
  chapters: number
  tokens: number
  calls: number
}

/** 场景用量 */
export interface DashboardSceneUsageVO {
  scene: string
  label: string
  group: string
  calls: number
  tokens: number
  percent: number
}

/** 最近编辑的小说 */
export interface DashboardRecentNovelVO {
  id: string
  title: string
  genre?: string
  status: string
  statusLabel: string
  chapterCount: number
  wordCount: number
  updatedAt?: string
}

/** 仪表盘总览 */
export interface DashboardOverviewVO {
  profile: DashboardProfileVO
  novel: DashboardNovelStatsVO
  chapter: DashboardChapterStatsVO
  ai: DashboardAiStatsVO
  trend: DashboardTrendPointVO[]
  scenes: DashboardSceneUsageVO[]
  recentNovels: DashboardRecentNovelVO[]
}

/** 拉取当前用户的仪表盘数据 */
export function getDashboardOverview() {
  return api.get<DashboardOverviewVO>('/dashboard/overview')
}
