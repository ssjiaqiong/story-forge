import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getSystemInfo } from '@/api/system'

/**
 * 部署版本号。
 *
 * <p>只从后端 `/api/system/info` 取——版本号的唯一来源是 pom.xml，
 * 前端不再用 package.json 或常量另存一份，避免两边对不上。</p>
 *
 * <p>页脚展示属于「有则更好」的信息：接口失败（离线、后端未起）就让它空着，
 * 不弹提示、不影响任何业务流程。</p>
 */
export const useSystemStore = defineStore('system', () => {
  /** 形如 `1.2.5`；取不到时为空串 */
  const version = ref('')
  const buildTime = ref('')
  let pending: Promise<void> | null = null

  /** 幂等加载：多处页脚同时挂载也只发一次请求 */
  function load(): Promise<void> {
    if (version.value) {
      return Promise.resolve()
    }
    if (!pending) {
      pending = getSystemInfo()
        .then((info) => {
          version.value = (info?.version || '').trim()
          buildTime.value = info?.buildTime || ''
        })
        .catch(() => {
          /* 拿不到版本不影响使用，保持空串 */
        })
        .finally(() => {
          pending = null
        })
    }
    return pending
  }

  return { version, buildTime, load }
})
