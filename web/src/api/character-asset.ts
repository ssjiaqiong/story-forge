import { api } from './request'

/** 人物属性（只有当前值，历史变化看「变更履历」） */
export interface AttributeVO {
  id: string
  characterId: string
  name: string
  value?: string
  unit?: string
  attrType: 'NUMBER' | 'TEXT' | string
  note?: string
  sortNo?: number
}

export interface AttributePayload {
  name: string
  value?: string
  unit?: string
  attrType?: string
  note?: string
  sortNo?: number
}

/** 背包条目：ITEM 关联物品档案，CURRENCY 货币，MISC 常规物资 */
export type InventoryEntryType = 'ITEM' | 'CURRENCY' | 'MISC'

export interface InventoryVO {
  id: string
  characterId: string
  entryType: InventoryEntryType
  entryTypeLabel: string
  itemId?: string
  name: string
  quantity?: number
  unit?: string
  status: 'HELD' | 'LOST' | 'CONSUMED' | string
  statusLabel: string
  note?: string
  itemDescription?: string
  itemOrigin?: string
  sortNo?: number
}

export interface InventoryPayload {
  entryType?: InventoryEntryType
  itemId?: string
  name?: string
  quantity?: number | null
  unit?: string
  status?: string
  note?: string
  sortNo?: number
  clearQuantity?: boolean
}

export const attributeApi = {
  list: (characterId: string) => api.get<AttributeVO[]>(`/bookshelf/characters/${characterId}/attributes`),
  create: (characterId: string, payload: AttributePayload) =>
    api.post<AttributeVO>(`/bookshelf/characters/${characterId}/attributes`, payload),
  update: (id: string, payload: AttributePayload) => api.put<AttributeVO>(`/bookshelf/attributes/${id}`, payload),
  remove: (id: string) => api.delete<void>(`/bookshelf/attributes/${id}`)
}

export const inventoryApi = {
  list: (characterId: string) => api.get<InventoryVO[]>(`/bookshelf/characters/${characterId}/inventory`),
  /** 整本书的背包按人物分组，一次取全 */
  listByNovel: (novelId: string) =>
    api.get<Record<string, InventoryVO[]>>(`/bookshelf/novels/${novelId}/inventory`),
  create: (characterId: string, payload: InventoryPayload) =>
    api.post<InventoryVO>(`/bookshelf/characters/${characterId}/inventory`, payload),
  update: (id: string, payload: InventoryPayload) => api.put<InventoryVO>(`/bookshelf/inventory/${id}`, payload),
  remove: (id: string) => api.delete<void>(`/bookshelf/inventory/${id}`)
}
