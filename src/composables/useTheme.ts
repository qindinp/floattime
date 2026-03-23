import { ref, computed, onMounted, onUnmounted, watch } from 'vue'
import type { ThemeMode } from '../types'
import { STORAGE_KEYS } from '../utils/config'
import { loadString, saveString } from '../utils/storage'

export function useTheme() {
  const themeMode = ref<ThemeMode>('auto')
  const systemIsDark = ref(false)
  let darkModeQuery: MediaQueryList | null = null
  let darkModeListener: ((e: MediaQueryListEvent) => void) | null = null

  const isDarkMode = computed(() => {
    if (themeMode.value === 'auto') return systemIsDark.value
    return themeMode.value === 'dark'
  })

  function setupThemeListener() {
    darkModeQuery = window.matchMedia('(prefers-color-scheme: dark)')
    systemIsDark.value = darkModeQuery.matches
    darkModeListener = (e: MediaQueryListEvent) => { systemIsDark.value = e.matches }
    darkModeQuery.addEventListener('change', darkModeListener)
  }

  function applyTheme() {
    const root = document.documentElement
    if (isDarkMode.value) {
      root.classList.add('dark-theme')
      root.classList.add('is-dark')
      root.classList.remove('light-theme')
    } else {
      root.classList.add('light-theme')
      root.classList.remove('dark-theme')
      root.classList.remove('is-dark')
    }
  }

  function setThemeMode(mode: ThemeMode) {
    themeMode.value = mode
    saveString(STORAGE_KEYS.THEME, mode)
  }

  onMounted(() => {
    const savedTheme = loadString(STORAGE_KEYS.THEME, 'auto') as ThemeMode
    themeMode.value = savedTheme
    setupThemeListener()
    applyTheme()
  })

  onUnmounted(() => {
    if (darkModeQuery && darkModeListener) {
      darkModeQuery.removeEventListener('change', darkModeListener)
    }
  })

  watch(isDarkMode, applyTheme, { immediate: true })

  return {
    themeMode,
    isDarkMode,
    setThemeMode,
  }
}
