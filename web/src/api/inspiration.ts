import { api } from './request'
import type { PageResult, NovelVO } from './novel'

/** 聊天与抽取可能耗时较长，单独放宽超时 */
const AI_TIMEOUT = 180_000

export interface InspirationMessage {
  id: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  status: 'OK' | 'FAILED'
  errorMessage?: string
  model?: string
  createdAt?: string
  /** 深度思考内容（流式过程中实时累积，落库后不保留） */
  reasoning?: string
}

export interface InspirationSession {
  id: string
  title?: string
  skillCode: string
  skillName?: string
  messageCount: number
  lastMessageAt?: string
  createdAt?: string
  messages: InspirationMessage[]
}

export interface SendMessageResult {
  userMessage: InspirationMessage
  assistantMessage: InspirationMessage
  session: InspirationSession
  errorMessage?: string
}

export interface InspirationSkill {
  id: string
  code: string
  name: string
  description?: string
  systemPrompt: string
  builtin: boolean
  enabled: boolean
  sortNo: number
}

export interface SkillPayload {
  name: string
  description?: string
  systemPrompt: string
}

export interface CreateNovelResult {
  novel: NovelVO
  extraction: 'AI' | 'FALLBACK'
  note?: string
}

/** 一键建书任务视图 */
export interface NovelCreationTask {
  taskId: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED'
  percent: number
  stepNo: number
  stepTotal: number
  stepLabel: string
  message?: string
  novelId?: string
  novelTitle?: string
  errorMessage?: string
}

export function getSession() {
  return api.get<InspirationSession>('/inspiration/session')
}

export function sendMessage(content: string, signal?: AbortSignal) {
  return api.post<SendMessageResult>('/inspiration/messages', { content }, AI_TIMEOUT, signal)
}

export function switchSkill(skillCode: string) {
  return api.put<InspirationSession>('/inspiration/session/skill', { skillCode })
}

/** 清空上下文：删除全部消息，保留会话与技能选择 */
export function clearContext() {
  return api.post<void>('/inspiration/session/clear')
}

/** 删除整个会话 */
export function deleteSession() {
  return api.delete<void>('/inspiration/session')
}

export function listSkills() {
  return api.get<InspirationSkill[]>('/inspiration/skills')
}

export function createSkill(payload: SkillPayload) {
  return api.post<InspirationSkill>('/inspiration/skills', payload)
}

export function updateSkill(id: string, payload: Partial<SkillPayload>) {
  return api.put<InspirationSkill>(`/inspiration/skills/${id}`, payload)
}

export function resetSkill(id: string) {
  return api.post<InspirationSkill>(`/inspiration/skills/${id}/reset`)
}

export function deleteSkill(id: string) {
  return api.delete<void>(`/inspiration/skills/${id}`)
}

export function createNovelFromSession() {
  return api.post<CreateNovelResult>('/inspiration/novels', {}, AI_TIMEOUT)
}

/** 启动一键建书任务（完整开书：世界观/背景/冲突/人物关系/事件/大纲/物品/势力） */
export function startNovelCreation() {
  return api.post<NovelCreationTask>('/inspiration/novels/tasks', {}, AI_TIMEOUT)
}

/** 查询一键建书任务进度 */
export function getNovelCreationTask(taskId: string) {
  return api.get<NovelCreationTask>(`/inspiration/novels/tasks/${taskId}`)
}

export type { PageResult, NovelVO }
