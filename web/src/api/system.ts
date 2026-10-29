import { api } from './request'

/** 部署信息（公开接口，无需登录） */
export interface SystemInfoVO {
  /** 构建版本号，取自后端 pom.xml 的 version */
  version: string
  /** 构建时间；直接跑源码时可能为空 */
  buildTime?: string
}

export function getSystemInfo() {
  return api.get<SystemInfoVO>('/system/info')
}
