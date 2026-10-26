import { defineStore } from 'pinia'
import { computed, ref } from 'vue'

export type ThemeMode = 'light' | 'dark' | 'system'

const STORAGE_KEY = 'storyforge.theme'

/** 读取本地保存的模式；非法值回落到 light */
function readMode(): ThemeMode {
  const saved = localStorage.getItem(STORAGE_KEY)
  return saved === 'dark' || saved === 'system' ? saved : 'light'
}

function systemPrefersDark(): boolean {
  return typeof window !== 'undefined' && window.matchMedia('(prefers-color-scheme: dark)').matches
}

export const useThemeStore = defineStore('theme', () => {
  const mode = ref<ThemeMode>(readMode())
  /** 系统配色（仅 mode = system 时参与计算） */
  const systemDark = ref(systemPrefersDark())
  /** 实际生效的是否深色 */
  const isDark = computed(() => (mode.value === 'system' ? systemDark.value : mode.value === 'dark'))
  const themeLabel = computed(() =>
    mode.value === 'light' ? '浅色' : mode.value === 'dark' ? '深色' : '跟随系统'
  )

  function applyDom() {
    const root = document.documentElement
    root.classList.toggle('dark', isDark.value)
    // 让原生控件（滚动条、输入框）也跟随
    root.style.colorScheme = isDark.value ? 'dark' : 'light'
  }

  function setMode(next: ThemeMode) {
    mode.value = next
    localStorage.setItem(STORAGE_KEY, next)
    applyDom()
  }

  /** 应用启动时调用：同步 DOM 并注册系统配色监听 */
  function init() {
    applyDom()
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    media.addEventListener('change', (event) => {
      systemDark.value = event.matches
      if (mode.value === 'system') {
        applyDom()
      }
    })
  }

  function toggle() {
    setMode(isDark.value ? 'light' : 'dark')
  }

  return { mode, isDark, themeLabel, setMode, toggle, init }
})
