import { api } from './request'

/** 助手执行的一条修改操作 */
export interface AgentOperation {
  type: string
  target?: string
  success: boolean
  message?: string
}

export interface AgentMessage {
  id: string
  role: 'USER' | 'ASSISTANT' | 'SYSTEM'
  content: string
  operations?: AgentOperation[]
  status: 'OK' | 'FAILED'
  errorMessage?: string
  model?: string
  createdAt?: string
}

export interface AgentSession {
  id: string
  novelId: string
  title?: string
  messageCount: number
  lastMessageAt?: string
  messages: AgentMessage[]
}

export interface AgentSendResult {
  userMessage: AgentMessage
  assistantMessage: AgentMessage
  session: AgentSession
}

/** 智能助手多步执行任务进度 */
export interface AgentTask {
  taskId: string
  status: 'RUNNING' | 'SUCCEEDED' | 'FAILED' | 'CANCELLED'
  percent: number
  turnNo: number
  turnTotal: number
  stepLabel: string
  operations?: AgentOperation[]
  reply?: string
  errorMessage?: string
  result?: AgentSendResult
}

/** 操作类型 → 中文名，用于摘要展示 */
export const AGENT_OPERATION_LABELS: Record<string, string> = {
  UPDATE_NOVEL: '基本信息',
  UPDATE_SETTING: '设定',
  CREATE_CHARACTER: '新增角色',
  UPDATE_CHARACTER: '修改角色',
  DELETE_CHARACTER: '删除角色',
  CREATE_ITEM: '新增物品',
  UPDATE_ITEM: '修改物品',
  DELETE_ITEM: '删除物品',
  CREATE_RELATION: '新增关系',
  UPDATE_RELATION: '修改关系',
  DELETE_RELATION: '删除关系',
  CREATE_FACTION: '新增势力',
  UPDATE_FACTION: '修改势力',
  DELETE_FACTION: '删除势力',
  ADD_FACTION_MEMBER: '添加势力成员',
  UPDATE_FACTION_MEMBER: '修改势力成员',
  REMOVE_FACTION_MEMBER: '移除势力成员',
  UPDATE_EVENT: '修改事件',
  DELETE_EVENT: '删除事件',
  CREATE_MASTER_OUTLINE: '新增总纲',
  UPDATE_MASTER_OUTLINE: '修改总纲',
  DELETE_MASTER_OUTLINE: '删除总纲',
  CREATE_CHAPTER: '新增章节',
  UPDATE_CHAPTER: '修改章节',
  DELETE_CHAPTER: '删除章节'
}

export function getAgentSession(novelId: string) {
  return api.get<AgentSession>(`/novels/${novelId}/agent/session`)
}

/** 发送助手消息：AI 可能执行多步修改，超时放宽到 5 分钟 */
export function sendAgentMessage(novelId: string, content: string) {
  return api.post<AgentSendResult>(`/novels/${novelId}/agent/messages`, { content }, 300000)
}

/** 启动智能助手多步任务，返回 taskId 供轮询 */
export function startAgentTask(novelId: string, content: string) {
  return api.post<AgentTask>(`/novels/${novelId}/agent/tasks`, { content }, 300000)
}

/** 查询智能助手任务进度 */
export function getAgentTask(novelId: string, taskId: string) {
  return api.get<AgentTask>(`/novels/${novelId}/agent/tasks/${taskId}`)
}

/** 查询正在执行的助手任务（刷新后恢复轮询） */
export function getRunningAgentTask(novelId: string) {
  return api.get<AgentTask | null>(`/novels/${novelId}/agent/tasks/running`)
}

/** 停止智能助手任务 */
export function cancelAgentTask(novelId: string, taskId: string) {
  return api.delete<AgentTask>(`/novels/${novelId}/agent/tasks/${taskId}`)
}

export function clearAgentSession(novelId: string) {
  return api.delete<void>(`/novels/${novelId}/agent/session`)
}
