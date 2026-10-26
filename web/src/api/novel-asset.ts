import { api, ApiError, ErrorCodes, refreshAccessToken, type ApiResult } from './request'
import { clearTokens, getAccessToken } from '@/utils/auth-storage'
import type { CharacterDraft } from './bookshelf'
import type { FactionVO } from './faction'
import type { PageResult } from './novel'
import type { SearchBundleVO } from './search'

/** 章节关联事件（轻量） */
export interface ChapterEventVO {
  id: string
  title: string
  status: string
  statusLabel: string
}

/** 章节（大纲 / 正文共用） */
export interface ChapterVO {
  id: string
  volumeNo: number
  volumeTitle?: string
  chapterNo: number
  title: string
  summary?: string
  /** 列表接口不返回正文 */
  content?: string
  wordCount: number
  status: string
  updatedAt?: string
  novelId?: string
  /** 本章参与人物 */
  participants: EventParticipantVO[]
  /** 本章关联事件 */
  events: ChapterEventVO[]
  /** 本章大纲关联的总纲 id */
  masterOutlineId?: string | null
}

export interface ChapterPayload {
  volumeNo?: number
  volumeTitle?: string
  chapterNo?: number
  title?: string
  summary?: string
  content?: string
  status?: string
  /** null 表示不修改，空数组表示清空 */
  participantIds?: string[] | null
  /** null 表示不修改，空数组表示清空 */
  eventIds?: string[] | null
  /** null 表示不修改，0 表示解除关联 */
  masterOutlineId?: string | number | null
}

export interface EventParticipantVO {
  id: string
  name: string
  roleLabel?: string
  roleDesc?: string
}

export interface TimelineVO {
  id: string
  eventTime?: string
  title: string
  description?: string
  /** UNUSED / ONGOING / DONE / ABANDONED */
  status: string
  statusLabel: string
  participants: EventParticipantVO[]
  sortNo: number
  updatedAt?: string
}

export interface TimelinePayload {
  eventTime?: string
  title: string
  description?: string
  status?: string
  participantIds?: string[]
  sortNo?: number
}

export interface ItemVO {
  id: string
  name: string
  category?: string
  owner?: string
  /** 物品描述：外貌形态、材质、特征与象征意义 */
  description?: string
  /** 说明：来历、经手人、关联的关键故事 */
  origin?: string
  /** ACTIVE / DESTROYED / ABANDONED / LOST / CONSUMED */
  status: string
  statusLabel: string
  /** 数量，可空（唯一或不可计数物品） */
  quantity?: number | null
  sortNo: number
  updatedAt?: string
}

export interface ItemPayload {
  name: string
  category?: string
  owner?: string
  description?: string
  origin?: string
  status?: string
  quantity?: number | null
  /** true 表示把数量清空（quantity 传 null 表示不修改） */
  clearQuantity?: boolean
  sortNo?: number
}

export interface ItemGenerateRequest {
  prompt: string
  count?: number
}

export interface ItemGenerateResultVO {
  items: ItemVO[]
  skipped: string[]
  model?: string
  latencyMs?: number
}

export interface CharacterVO extends CharacterDraft {
  id?: string
  roleLabel?: string
}

/* ---------------- 章节 ---------------- */

export function listChapters(novelId: string) {
  return api.get<ChapterVO[]>(`/bookshelf/novels/${novelId}/chapters`)
}

export function getChapter(id: string) {
  return api.get<ChapterVO>(`/bookshelf/chapters/${id}`)
}

export function createChapter(novelId: string, payload: ChapterPayload) {
  return api.post<ChapterVO>(`/bookshelf/novels/${novelId}/chapters`, payload, 30000)
}

export function updateChapter(id: string, payload: ChapterPayload) {
  return api.put<ChapterVO>(`/bookshelf/chapters/${id}`, payload, 30000)
}

export function deleteChapter(id: string) {
  return api.delete<void>(`/bookshelf/chapters/${id}`)
}

/* ---------------- 时间线 ---------------- */

export function listTimeline(novelId: string) {
  return api.get<TimelineVO[]>(`/bookshelf/novels/${novelId}/timeline`)
}

export function updateTimeline(id: string, payload: TimelinePayload) {
  return api.put<TimelineVO>(`/bookshelf/timeline/${id}`, payload)
}

export function deleteTimeline(id: string) {
  return api.delete<void>(`/bookshelf/timeline/${id}`)
}

/* ---------------- 物品 ---------------- */

export function listItems(novelId: string) {
  return api.get<ItemVO[]>(`/bookshelf/novels/${novelId}/items`)
}

export function createItem(novelId: string, payload: ItemPayload) {
  return api.post<ItemVO>(`/bookshelf/novels/${novelId}/items`, payload)
}

export function updateItem(id: string, payload: ItemPayload) {
  return api.put<ItemVO>(`/bookshelf/items/${id}`, payload)
}

export function deleteItem(id: string) {
  return api.delete<void>(`/bookshelf/items/${id}`)
}

/* ---------------- 角色 ---------------- */

export function listCharacters(novelId: string) {
  return api.get<CharacterVO[]>(`/bookshelf/novels/${novelId}/characters`)
}

export function createCharacter(novelId: string, payload: CharacterDraft) {
  return api.post<CharacterVO>(`/bookshelf/novels/${novelId}/characters`, payload)
}

export function updateCharacter(id: string, payload: CharacterDraft) {
  return api.put<CharacterVO>(`/bookshelf/characters/${id}`, payload)
}

export function deleteCharacter(id: string) {
  return api.delete<void>(`/bookshelf/characters/${id}`)
}

/* ---------------- 人物关系 ---------------- */

export interface RelationVO {
  id: string
  fromCharacterId: string
  fromCharacterName: string
  toCharacterId: string
  toCharacterName: string
  relationType: string
  relationLabel: string
  description?: string
  sortNo: number
}

export interface RelationPayload {
  fromCharacterId: string
  toCharacterId: string
  relationType: string
  description?: string
  sortNo?: number
}

export interface RelationTreeNode {
  characterId: string
  name: string
  identity?: string
  rolePos?: string
  roleLabel: string
  relationType?: string
  relationLabel?: string
  children: RelationTreeNode[]
}

export function listRelations(novelId: string) {
  return api.get<RelationVO[]>(`/bookshelf/novels/${novelId}/relations`)
}

export function getRelationTree(novelId: string) {
  return api.get<RelationTreeNode[]>(`/bookshelf/novels/${novelId}/relations/tree`)
}

export function createRelation(novelId: string, payload: RelationPayload) {
  return api.post<RelationVO>(`/bookshelf/novels/${novelId}/relations`, payload)
}

export function updateRelation(id: string, payload: RelationPayload) {
  return api.put<RelationVO>(`/bookshelf/relations/${id}`, payload)
}

export function deleteRelation(id: string) {
  return api.delete<void>(`/bookshelf/relations/${id}`)
}

/* ---------------- AI 创建角色 ---------------- */

export interface CharacterGenerateRequest {
  prompt: string
  count?: number
}

export interface CharacterGenerateResultVO {
  characters: CharacterVO[]
  relations: RelationVO[]
  skipped: string[]
  model?: string
  latencyMs?: number
}

/** 一段提示语生成角色与关系；单次可能较久，单独放大超时 */
export function generateCharacters(novelId: string, payload: CharacterGenerateRequest) {
  return api.post<CharacterGenerateResultVO>(`/bookshelf/novels/${novelId}/characters/generate`, payload, 300000)
}

/* ---------------- AI 生成事件 ---------------- */

export interface EventGenerateRequest {
  prompt: string
  count?: number
  /** 是否联网检索资料，默认 true */
  useSearch?: boolean
  searchQuery?: string
}

export interface EventGenerateResultVO {
  events: TimelineVO[]
  createdCharacters: CharacterVO[]
  skipped: string[]
  model?: string
  latencyMs?: number
  search?: SearchBundleVO
}

/** 事件只能通过 AI 创建；单次最多 10 个，超时放宽到 5 分钟 */
export function generateEvents(novelId: string, payload: EventGenerateRequest) {
  return api.post<EventGenerateResultVO>(`/bookshelf/novels/${novelId}/timeline/generate`, payload, 300000)
}


/* ---------------- AI 生成大纲 ---------------- */

export interface MasterOutlineVO {
  id: string
  sortNo: number
  title: string
  content?: string
  /** 已关联的章节大纲数量 */
  chapterCount: number
  updatedAt?: string
}

export interface MasterOutlinePayload {
  title?: string
  content?: string
  sortNo?: number
}

export interface MasterOutlineGeneratePayload {
  prompt?: string
  count?: number
}

export function listMasterOutlines(novelId: string) {
  return api.get<MasterOutlineVO[]>(`/bookshelf/novels/${novelId}/master-outlines`)
}

export function createMasterOutline(novelId: string, payload: MasterOutlinePayload) {
  return api.post<MasterOutlineVO>(`/bookshelf/novels/${novelId}/master-outlines`, payload)
}

export function updateMasterOutline(id: string, payload: MasterOutlinePayload) {
  return api.put<MasterOutlineVO>(`/bookshelf/master-outlines/${id}`, payload)
}

export function deleteMasterOutline(id: string) {
  return api.delete<void>(`/bookshelf/master-outlines/${id}`)
}

/** AI 生成总纲：资料充足覆盖全书，不足只生成一部分；超时 5 分钟 */
export function generateMasterOutlines(novelId: string, payload: MasterOutlineGeneratePayload) {
  return api.post<MasterOutlineVO[]>(`/bookshelf/novels/${novelId}/master-outlines/generate`, payload, 300000)
}

export interface OutlineGeneratePayload {
  prompt?: string
  volumeNo?: number
  volumeTitle?: string
  chapterCount?: number
  startChapterNo?: number
  /** 指定出场人物：本批每章都会包含，留空由 AI 按设定挑选 */
  participantIds?: string[]
  useSearch?: boolean
  searchQuery?: string
  /** 关联的总纲 id：这批章节大纲必须落在该总纲范围内，并自动关联 */
  masterOutlineId?: string | null
}

export interface OutlineGenerateResultVO {
  chapters: ChapterVO[]
  skipped: string[]
  model?: string
  latencyMs?: number
  search?: SearchBundleVO
  /** 生成阶段顺带初始化出来的档案，便于前端提示「本次补建了谁」 */
  newCharacters?: CharacterVO[]
  newItems?: ItemVO[]
  newFactions?: FactionVO[]
  newEvents?: TimelineVO[]
}

/** 一次最多 20 章，超时 5 分钟 */
export function generateOutline(novelId: string, payload: OutlineGeneratePayload) {
  return api.post<OutlineGenerateResultVO>(`/bookshelf/novels/${novelId}/outline/generate`, payload, 300000)
}

/* ---------------- AI 写正文 ---------------- */

export interface ContentGeneratePayload {
  prompt?: string
  targetWords?: number
  /** REPLACE 覆盖 / APPEND 追加 */
  mode?: 'REPLACE' | 'APPEND'
  useSearch?: boolean
  searchQuery?: string
}

export interface ContentGenerateResultVO {
  chapterId: string
  content: string
  generated: string
  wordCount: number
  generatedWords: number
  mode: string
  model?: string
  latencyMs?: number
  search?: SearchBundleVO
  /** 写完正文后顺带补建的档案：本章新出现的人 / 道具 / 组织 */
  newCharacters?: CharacterVO[]
  newItems?: ItemVO[]
  newFactions?: FactionVO[]
}

/**
 * 单章最多 6000 字，超时 8 分钟：
 * 一次请求里有两步 AI 调用——先写正文（最长 5 分钟），再补建本章新出现的人物 / 物品 / 势力档案。
 */
export function generateContent(chapterId: string, payload: ContentGeneratePayload) {
  return api.post<ContentGenerateResultVO>(`/bookshelf/chapters/${chapterId}/generate`, payload, 480000)
}

/* ------------------------------------------------------------------ */
/* 批量连写 N 章                                                      */
/* ------------------------------------------------------------------ */

/** 单章在连写任务里的状态 */
export type WriteTaskChapterStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'SKIPPED'
  | 'CANCELLED'

export interface WriteTaskChapterVO {
  chapterId: string
  chapterNo: number
  title?: string
  status?: WriteTaskChapterStatus | string
  wordCount?: number
  warning?: string
  error?: string
}

export interface WriteTaskVO {
  taskId: string
  novelId: string
  /** RUNNING / SUCCEEDED / PARTIAL / FAILED / CANCELLED */
  status: string
  total: number
  done: number
  percent: number
  currentChapterNo?: number
  currentTitle?: string
  stepLabel?: string
  writtenWords?: number
  succeeded: number
  failed: number
  skipped: number
  chapters: WriteTaskChapterVO[]
  message?: string
  errorMessage?: string
  startedAt?: number
  finishedAt?: number
}

export interface WriteTaskPayload {
  fromChapterId?: string
  fromChapterNo?: number
  /** 1~10 */
  count?: number
  /** 300~6000，留空取本书近期章节平均字数 */
  targetWords?: number | null
  requirement?: string
  stopOnError?: boolean
  skipDone?: boolean
  useSearch?: boolean
}

export function startWriteTask(novelId: string, payload: WriteTaskPayload) {
  return api.post<WriteTaskVO>(`/bookshelf/novels/${novelId}/write-tasks`, payload)
}

export function getWriteTask(taskId: string) {
  return api.get<WriteTaskVO>(`/bookshelf/write-tasks/${taskId}`)
}

export function cancelWriteTask(taskId: string) {
  return api.delete<WriteTaskVO>(`/bookshelf/write-tasks/${taskId}`)
}

/* ------------------------------------------------------------------ */
/* 流式写正文                                                          */
/* ------------------------------------------------------------------ */

/** 生成开始前的元信息：检索已完成，正文还没开始写 */
export interface ContentStreamMeta {
  model?: string
  targetWords: number
  mode: string
  search?: SearchBundleVO
}

export interface ContentStreamHandlers {
  onMeta?: (meta: ContentStreamMeta) => void
  /** 一个增量；可能含换行，累加即可，不要按行渲染 */
  onDelta?: (text: string) => void
  /** 由调用方在 fetch 结束后自行取返回值，这里不重复回调 */
  signal?: AbortSignal
}

/**
 * 流式写正文（SSE）。
 *
 * <p>用 fetch 而不是 axios：axios 的 XHR 适配器拿不到流式响应体，只能等整段结束。
 * 代价是拦截器里的逻辑要手写一遍——401 时手动刷新令牌后重试一次。</p>
 *
 * <p>两种失败形式都要处理：</p>
 * <ul>
 *   <li>建流之前就失败（本章正在生成、限流、参数非法）→ 普通 JSON，直接抛 {@link ApiError}；</li>
 *   <li>建流之后失败 → {@code error} 事件，同样抛 {@link ApiError}。</li>
 * </ul>
 *
 * @returns 成功时的完整结果；连接中途断掉（未收到 done）时抛错，已收到的文本由调用方保留
 */
export async function generateContentStream(
  chapterId: string,
  payload: ContentGeneratePayload,
  handlers: ContentStreamHandlers = {}
): Promise<ContentGenerateResultVO> {
  const url = `/api/bookshelf/chapters/${chapterId}/generate/stream`
  if (typeof ReadableStream === 'undefined') {
    // 浏览器不支持流式读取：退回同步接口，结果一样，只是没有打字机效果
    const result = await api.post<ContentGenerateResultVO>(
      `/bookshelf/chapters/${chapterId}/generate`, payload, 480000)
    handlers.onMeta?.({
      model: result.model,
      targetWords: payload.targetWords ?? 2500,
      mode: payload.mode ?? 'REPLACE',
      search: result.search
    })
    handlers.onDelta?.(result.generated || result.content || '')
    return result
  }
  const send = () => {
    const token = getAccessToken()
    return fetch(url, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      body: JSON.stringify(payload),
      signal: handlers.signal
    })
  }

  let response = await send()
  if (response.status === 401) {
    const refreshed = await refreshAccessToken()
    if (!refreshed) {
      clearTokens()
      if (window.location.pathname !== '/login') {
        window.location.href = `/login?redirect=${encodeURIComponent(window.location.pathname + window.location.search)}`
      }
      throw new ApiError(ErrorCodes.UNAUTHORIZED, '登录状态已失效，请重新登录')
    }
    response = await send()
  }

  const contentType = response.headers.get('Content-Type') || ''
  if (!contentType.includes('text/event-stream')) {
    // 建流前失败：后端按普通 JSON 返回统一响应体
    const body = (await response.json().catch(() => null)) as ApiResult | null
    throw new ApiError(body?.code ?? response.status ?? -1, body?.message || `请求失败（HTTP ${response.status}）`)
  }
  if (!response.body) {
    throw new ApiError(-1, '当前浏览器不支持流式读取，请升级浏览器')
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8')
  let buffer = ''
  let result: ContentGenerateResultVO | null = null
  let failure: ApiError | null = null

  const dispatch = (event: string, data: string) => {
    let parsed: Record<string, unknown>
    try {
      parsed = JSON.parse(data) as Record<string, unknown>
    } catch {
      return // 脏帧跳过，不中断整条流
    }
    if (event === 'meta') {
      handlers.onMeta?.(parsed as unknown as ContentStreamMeta)
      return
    }
    if (event === 'delta') {
      const text = typeof parsed.text === 'string' ? parsed.text : ''
      if (text) {
        handlers.onDelta?.(text)
      }
      return
    }
    if (event === 'done') {
      result = parsed as unknown as ContentGenerateResultVO
      return
    }
    if (event === 'error') {
      failure = new ApiError(Number(parsed.code ?? -1), String(parsed.message || 'AI 生成失败'))
    }
  }

  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) {
        break
      }
      buffer += decoder.decode(value, { stream: true })
      for (;;) {
        const match = /\r?\n\r?\n/.exec(buffer)
        if (!match) {
          break
        }
        const frame = buffer.slice(0, match.index)
        buffer = buffer.slice(match.index + match[0].length)
        let eventName = 'message'
        const dataLines: string[] = []
        for (const line of frame.split(/\r?\n/)) {
          if (line.startsWith(':')) {
            continue
          }
          if (line.startsWith('event:')) {
            eventName = line.slice(6).trim()
          } else if (line.startsWith('data:')) {
            dataLines.push(line.slice(5).replace(/^ /, ''))
          }
        }
        if (dataLines.length) {
          dispatch(eventName, dataLines.join('\n'))
        }
      }
    }
  } finally {
    reader.releaseLock()
  }

  if (failure) {
    throw failure
  }
  if (!result) {
    throw new ApiError(-1, '生成连接意外中断，已收到的内容保留在编辑区')
  }
  return result
}


/* ---------------- AI 创建物品 ---------------- */

/** 按提示语结合作品设定与角色生成物品；单次最多 10 个，超时 5 分钟 */
export function generateItems(novelId: string, payload: ItemGenerateRequest) {
  return api.post<ItemGenerateResultVO>(`/bookshelf/novels/${novelId}/items/generate`, payload, 300000)
}

/* ---------------- 章节正文历史版本 ---------------- */

/** 历史版本列表项（不含正文） */
export interface ChapterVersionVO {
  id: string
  versionNo: number
  source: string
  /** 来源中文名，后端已翻译，前端不再维护映射 */
  sourceLabel: string
  title?: string
  wordCount: number
  note?: string
  createdAt: string
}

/** 单个历史版本全文 */
export interface ChapterVersionDetailVO extends ChapterVersionVO {
  content: string
}

/** 历史版本列表，按版本号倒序 */
export function listChapterVersions(chapterId: string, page = 1, size = 20) {
  return api.get<PageResult<ChapterVersionVO>>(`/bookshelf/chapters/${chapterId}/versions`, { page, size })
}

/** 历史版本条数，用于角标 */
export function countChapterVersions(chapterId: string) {
  return api.get<number>(`/bookshelf/chapters/${chapterId}/versions/count`)
}

/** 单版全文 */
export function getChapterVersion(chapterId: string, versionId: string) {
  return api.get<ChapterVersionDetailVO>(`/bookshelf/chapters/${chapterId}/versions/${versionId}`)
}

/** 回滚到该版本；回滚前的稿子会先存为新版本 */
export function restoreChapterVersion(chapterId: string, versionId: string) {
  return api.post<ChapterVO>(`/bookshelf/chapters/${chapterId}/versions/${versionId}/restore`, {}, 60000)
}

/** 删除历史版本，不影响当前正文 */
export function deleteChapterVersion(chapterId: string, versionId: string) {
  return api.delete<void>(`/bookshelf/chapters/${chapterId}/versions/${versionId}`)
}
