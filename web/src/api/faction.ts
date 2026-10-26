import { api } from './request'

/** 势力成员（角色 + 职位） */
export interface FactionMemberVO {
  id: string
  characterId: string
  name: string
  roleLabel?: string
  position: string
}

/** 势力 */
export interface FactionVO {
  id: string
  name: string
  category?: string
  remark?: string
  scope?: string
  status: string
  statusLabel: string
  memberCount: number
  members: FactionMemberVO[]
  updatedAt?: string
}

export interface FactionPayload {
  name: string
  category?: string
  remark?: string
  scope?: string
  status?: string
  sortNo?: number
}

export function listFactions(novelId: string) {
  return api.get<FactionVO[]>(`/bookshelf/novels/${novelId}/factions`)
}

export function createFaction(novelId: string, payload: FactionPayload) {
  return api.post<FactionVO>(`/bookshelf/novels/${novelId}/factions`, payload)
}

export function updateFaction(id: string, payload: FactionPayload) {
  return api.put<FactionVO>(`/bookshelf/factions/${id}`, payload)
}

export function deleteFaction(id: string) {
  return api.delete<void>(`/bookshelf/factions/${id}`)
}

export function addFactionMember(factionId: string, payload: { characterId: string; position: string }) {
  return api.post<FactionMemberVO>(`/bookshelf/factions/${factionId}/members`, payload)
}

export function updateFactionMember(memberId: string, position: string) {
  return api.put<FactionMemberVO>(`/bookshelf/faction-members/${memberId}`, { position })
}

export function removeFactionMember(memberId: string) {
  return api.delete<void>(`/bookshelf/faction-members/${memberId}`)
}
