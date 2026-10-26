import { api, http } from './request'

/** 小说封面 */
export interface NovelCoverVO {
  id: string
  novelId: string
  /** 取图地址：需要带登录态请求，前端鉴权后转成 blob 展示 */
  imageUrl: string
  mimeType?: string
  fileSize?: number
  width?: number
  height?: number
  prompt?: string
  model?: string
  provider?: string
  /** 封面风格编码 */
  style?: string
  /** 是否已合成书名/作者文字 */
  hasText?: boolean
  titleText?: string
  authorText?: string
  /** 文字渲染方式：OVERLAY 本地叠加 / AI_NATIVE 模型直出 */
  textMode?: string
  primary: boolean
  sortNo: number
  createdAt?: string
}

/** 生成封面入参 */
export interface CoverGeneratePayload {
  /** 留空则按小说信息自动拼装提示词 */
  prompt?: string
  negativePrompt?: string
  size?: string
  /** 封面风格：ANIME/REAL/CG/INK/OIL/FLAT */
  style?: string
  /** 是否合成书名文字 */
  withTitle?: boolean
  /** 是否合成作者名文字 */
  withAuthor?: boolean
  /** 文字渲染方式：OVERLAY 后期叠加（默认）/ AI_NATIVE 模型直出 */
  textRenderMode?: 'OVERLAY' | 'AI_NATIVE'
  /** 书名文字（留空用小说书名） */
  titleText?: string
  /** 作者名文字（留空用小说笔名，仍无则 AI 起） */
  authorText?: string
}

export function listCovers(novelId: string) {
  return api.get<NovelCoverVO[]>(`/novels/${novelId}/covers`)
}

/** 生成封面：出图 + 下载 + 转码可能要一两分钟，超时给到 300 秒 */
export function generateCover(novelId: string, payload: CoverGeneratePayload) {
  return api.post<NovelCoverVO>(`/novels/${novelId}/covers/generate`, payload, 300_000)
}

export function setPrimaryCover(coverId: string) {
  return api.put<NovelCoverVO>(`/novels/covers/${coverId}/primary`)
}

export function deleteCover(coverId: string) {
  return api.delete<void>(`/novels/covers/${coverId}`)
}

/* ------------------------------------------------------------------ */
/* 图片加载：封面是私有资源，用带 Authorization 的请求取 blob 再交给 img  */
/* ------------------------------------------------------------------ */

const objectUrls = new Map<string, string>()
const pending = new Map<string, Promise<string>>()

/** 取封面图片的 blob 地址（同一封面只请求一次，页面内复用）。 */
export async function loadCoverUrl(coverId: string): Promise<string> {
  const cached = objectUrls.get(coverId)
  if (cached) {
    return cached
  }
  const running = pending.get(coverId)
  if (running) {
    return running
  }
  const task = http
    .get(`/novels/covers/${coverId}/image`, { responseType: 'blob' })
    .then((response) => {
      const url = URL.createObjectURL(response.data as Blob)
      objectUrls.set(coverId, url)
      pending.delete(coverId)
      return url
    })
    .catch((error) => {
      pending.delete(coverId)
      throw error
    })
  pending.set(coverId, task)
  return task
}

/** 封面被删除后释放内存中的 blob。 */
export function forgetCoverUrl(coverId: string): void {
  const url = objectUrls.get(coverId)
  if (url) {
    URL.revokeObjectURL(url)
    objectUrls.delete(coverId)
  }
}
