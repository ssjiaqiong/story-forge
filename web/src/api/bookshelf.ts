import { api, http } from './request'
import type { NovelVO } from './novel'

/** 角色设定（AI 生成 / 编辑共用） */
export interface CharacterDraft {
  id?: string
  name?: string
  rolePos?: string
  roleLabel?: string
  identity?: string
  gender?: string
  ageDesc?: string
  appearance?: string
  personality?: string
  motivation?: string
  goldenFinger?: string
  arc?: string
}

/** 小说蓝图 */
export interface NovelBlueprint {
  title?: string
  genre?: string
  tags: string[]
  synopsis?: string
  worldview?: string
  background?: string
  coreConflict?: string
  highlights: string[]
  style?: string
  protagonist?: CharacterDraft
  characters: CharacterDraft[]
}

/** 生成蓝图入参 */
export interface BlueprintRequest {
  title?: string
  genre?: string
  tags?: string[]
  idea: string
  style?: string
  targetLength?: string
  protagonistHint?: string
}

export interface BlueprintResultVO {
  blueprint: NovelBlueprint
  model?: string
  latencyMs?: number
}

/** 分段生成任务（进度轮询） */
export interface BlueprintTaskVO {
  taskId: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  percent: number
  stepNo: number
  stepTotal: number
  stepLabel: string
  message?: string
  result?: BlueprintResultVO
  errorMessage?: string
}

export interface NovelCreateRequest {
  title: string
  penName?: string
  genre?: string
  tags?: string[]
  intro?: string
  status?: string
  blueprint?: NovelBlueprint
}

export interface NovelEditRequest {
  title?: string
  penName?: string
  genre?: string
  tags?: string[]
  intro?: string
  status?: string
  blueprint?: NovelBlueprint
}

export interface SettingDocVO {
  category: string
  label: string
  content: string
}

export interface NovelDetailVO {
  novel: NovelVO
  settings: SettingDocVO[]
  characters: CharacterDraft[]
}

/** 生成蓝图：AI 调用耗时较长，单独放大超时 */
export function generateBlueprint(payload: BlueprintRequest) {
  return api.post<BlueprintResultVO>('/bookshelf/blueprint', payload, 300000)
}

/** 启动分段生成任务（世界观 / 背景 / 冲突 / 人物），返回 taskId */
export function startBlueprintTask(payload: BlueprintRequest) {
  return api.post<BlueprintTaskVO>('/bookshelf/blueprint/tasks', payload, 30000)
}

/** 轮询生成进度 */
export function getBlueprintTask(taskId: string) {
  return api.get<BlueprintTaskVO>(`/bookshelf/blueprint/tasks/${taskId}`)
}

export function createNovel(payload: NovelCreateRequest) {
  return api.post<NovelVO>('/bookshelf/novels', payload, 60000)
}

export function getNovelDetail(id: string) {
  return api.get<NovelDetailVO>(`/bookshelf/novels/${id}`)
}

export function updateNovel(id: string, payload: NovelEditRequest) {
  return api.put<NovelVO>(`/bookshelf/novels/${id}`, payload, 60000)
}

export function deleteNovel(id: string) {
  return api.delete<void>(`/bookshelf/novels/${id}`)
}

/** 导出整本小说为 JSON（封面以 Base64 内嵌），返回二进制用于直接下载 */
export async function exportNovel(id: string): Promise<Blob> {
  const response = await http.get<Blob>(`/bookshelf/novels/${id}/export`, {
    responseType: 'blob',
    timeout: 180000
  })
  return response.data
}

/** 导入小说 JSON，返回新建的小说（id 为当前账号下的新 id） */
export function importNovel(pkg: unknown) {
  return api.post<NovelVO>('/bookshelf/novels/import', pkg, 300000)
}

/** 空角色原型 */
export function emptyCharacter(rolePos = 'SUPPORTING'): CharacterDraft {
  return {
    name: '',
    rolePos,
    identity: '',
    gender: '',
    ageDesc: '',
    appearance: '',
    personality: '',
    motivation: '',
    goldenFinger: '',
    arc: ''
  }
}

/** 创建空蓝图（原型），避免各页面重复写默认值 */
export function emptyBlueprint(): NovelBlueprint {
  return {
    title: '',
    genre: '',
    tags: [],
    synopsis: '',
    worldview: '',
    background: '',
    coreConflict: '',
    highlights: [],
    style: '',
    protagonist: emptyCharacter('PROTAGONIST'),
    characters: []
  }
}

/** 兜底补全 AI 返回的蓝图（模型可能漏字段） */
export function normalizeBlueprint(source?: Partial<NovelBlueprint> | null): NovelBlueprint {
  const empty = emptyBlueprint()
  if (!source) {
    return empty
  }
  return {
    title: source.title ?? '',
    genre: source.genre ?? '',
    tags: source.tags ?? [],
    synopsis: source.synopsis ?? '',
    worldview: source.worldview ?? '',
    background: source.background ?? '',
    coreConflict: source.coreConflict ?? '',
    highlights: source.highlights ?? [],
    style: source.style ?? '',
    protagonist: { ...emptyCharacter('PROTAGONIST'), ...(source.protagonist ?? {}) },
    characters: (source.characters ?? []).map((item) => ({ ...emptyCharacter(), ...item }))
  }
}

