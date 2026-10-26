import { api } from './request'

/** 搜索源元数据：与后端 SearchProvider 枚举保持一致 */
export interface SearchProviderMeta {
  value: string
  label: string
  requiresKey: boolean
  baseUrl: string
  description: string
}

export const SEARCH_PROVIDERS: SearchProviderMeta[] = [
  {
    value: 'BING',
    label: '必应',
    requiresKey: false,
    baseUrl: 'https://cn.bing.com',
    description: '免密钥，抓取必应网页结果，国内网络通常可直接使用。'
  },
  {
    value: 'DUCKDUCKGO',
    label: 'DuckDuckGo',
    requiresKey: false,
    baseUrl: 'https://lite.duckduckgo.com',
    description: '免密钥，DuckDuckGo 精简版，部分网络环境无法访问。'
  },
  {
    value: 'WIKIPEDIA',
    label: '维基百科',
    requiresKey: false,
    baseUrl: 'https://zh.wikipedia.org',
    description: '免密钥，适合查名词、历史、地理等百科资料，不适合新闻。'
  },
  {
    value: 'SEARXNG',
    label: 'SearXNG',
    requiresKey: false,
    baseUrl: 'https://searx.be',
    description: '免密钥，自建或公共实例，需要在实例中开启 JSON 输出。'
  },
  {
    value: 'TAVILY',
    label: 'Tavily',
    requiresKey: true,
    baseUrl: 'https://api.tavily.com',
    description: '面向 AI 的搜索 API，需要 API Key，按量计费。'
  },
  {
    value: 'SERPER',
    label: 'Serper',
    requiresKey: true,
    baseUrl: 'https://google.serper.dev',
    description: 'Google 搜索结果 API，需要 API Key，按量计费。'
  }
]

export interface SearchConfigVO {
  id: string
  name: string
  provider: string
  providerLabel: string
  providerDescription?: string
  requiresKey: boolean
  baseUrl?: string
  apiKeyMask?: string
  /** 明文密钥，仅详情接口返回 */
  apiKey?: string
  apiKeyConfigured: boolean
  defaultTopK: number
  language: string
  region?: string
  timeoutMs: number
  enabled: boolean
  isDefault: boolean
  remark?: string
  lastTestAt?: string
  lastTestOk?: boolean
  lastTestLatencyMs?: number
  lastTestMessage?: string
  createdAt?: string
}

export interface SearchConfigPayload {
  name?: string
  provider?: string
  baseUrl?: string
  /** 编辑时留空表示保留原密钥 */
  apiKey?: string
  defaultTopK?: number
  language?: string
  region?: string
  timeoutMs?: number
  enabled?: boolean
  isDefault?: boolean
  remark?: string
}

export interface SearchResultVO {
  title: string
  url: string
  snippet?: string
  host?: string
}

export interface SearchTestResultVO {
  ok: boolean
  provider?: string
  providerLabel?: string
  latencyMs: number
  results: SearchResultVO[]
  message: string
}

/** 一次检索的完整结果（生成接口也会回传） */
export interface SearchBundleVO {
  used: boolean
  provider?: string
  providerLabel?: string
  query?: string
  topK?: number
  results: SearchResultVO[]
  error?: string
  elapsedMs: number
}

export interface SearchSceneVO {
  code: string
  label: string
  group: string
  enabled: boolean
  configId?: string
  configName?: string
  topK?: number
  effectiveConfigName?: string
}

export interface SearchSceneItem {
  code: string
  enabled: boolean
  configId?: string | null
  topK?: number | null
}

export function listSearchConfigs() {
  return api.get<SearchConfigVO[]>('/search-configs')
}

export function getSearchConfig(id: string) {
  return api.get<SearchConfigVO>(`/search-configs/${id}`)
}

export function createSearchConfig(payload: SearchConfigPayload) {
  return api.post<SearchConfigVO>('/search-configs', payload)
}

export function updateSearchConfig(id: string, payload: SearchConfigPayload) {
  return api.put<SearchConfigVO>(`/search-configs/${id}`, payload)
}

export function deleteSearchConfig(id: string) {
  return api.delete<void>(`/search-configs/${id}`)
}

export function setDefaultSearchConfig(id: string) {
  return api.post<SearchConfigVO>(`/search-configs/${id}/default`)
}

export function toggleSearchConfig(id: string, enabled: boolean) {
  return api.post<SearchConfigVO>(`/search-configs/${id}/status?enabled=${enabled}`)
}

export function testSearchConfig(id: string, query?: string) {
  return api.post<SearchTestResultVO>(`/search-configs/${id}/test`, { query }, 60000)
}

export interface SearchProbePayload {
  id?: string
  provider?: string
  baseUrl?: string
  apiKey?: string
  topK?: number
  query?: string
}

export function probeSearchConfig(payload: SearchProbePayload) {
  return api.post<SearchTestResultVO>('/search-configs/test', payload, 60000)
}

export function listSearchScenes() {
  return api.get<SearchSceneVO[]>('/search/scenes')
}

export function saveSearchScenes(items: SearchSceneItem[]) {
  return api.put<void>('/search/scenes', { items })
}

export function previewSearch(payload: { query: string; configId?: string; topK?: number }) {
  return api.post<SearchBundleVO>('/search/preview', payload, 60000)
}
