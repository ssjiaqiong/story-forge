/**
 * 项目来源与许可信息。
 *
 * AGPL-3.0 §13（远程网络交互）要求：修改后的程序若对外提供网络服务，
 * 必须让使用者能够获取对应的源码。二次开发者部署前请把 SOURCE_REPO_URL
 * 换成自己的公开仓库地址，否则不符合第 13 条。
 */

/** 协议简称，界面展示用 */
export const LICENSE_NAME = 'AGPL-3.0'

/** 源码仓库（公开） */
export const SOURCE_REPO_URL = 'https://gitee.com/jiaqiong/story-forge'

/** 协议全文 */
export const LICENSE_URL = `${SOURCE_REPO_URL}/blob/master/LICENSE`

/** 外部链接统一走新窗口，noopener/noreferrer 防止被打开页面操纵本页 */
export function openExternal(url: string): void {
  window.open(url, '_blank', 'noopener,noreferrer')
}
