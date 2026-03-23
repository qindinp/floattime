import { ref, computed } from 'vue'
import type { TimeSource, SyncStatus } from '../types'
import { CORS_PROXIES, TIME_ENDPOINTS } from '../utils/config'
import { fetchWithProxy, parseServerTime, getNestedValue } from '../utils/time'
import { liveUpdateManager } from '../utils/liveUpdate'

export function useTimeSync() {
  const timeSource = ref<TimeSource>('taobao')
  const offsetMs = ref(0)
  const syncStatus = ref<SyncStatus>('idle')
  const lastSyncTime = ref('')

  const currentSourceLabel = computed(() => {
    if (timeSource.value === 'taobao') return '淘宝时间'
    if (timeSource.value === 'meituan') return '美团时间'
    return '本地时间'
  })

  const themeColor = computed(() => {
    if (timeSource.value === 'taobao') return '#FF5000'
    if (timeSource.value === 'meituan') return '#FFD100'
    return '#4A90E2'
  })

  const diffDisplay = computed(() => {
    const diff = offsetMs.value
    const absD = Math.abs(diff)
    return absD < 50 ? '±0ms' : diff >= 0 ? `+${absD}ms` : `-${absD}ms`
  })

  async function syncTime(source: TimeSource): Promise<number> {
    if (source === 'local') return 0

    const candidates = TIME_ENDPOINTS[source] || []

    for (const ep of candidates) {
      try {
        const controller = new AbortController()
        const timeoutId = setTimeout(() => controller.abort(), 8000)

        try {
          const localBefore = Date.now()
          const resp = await fetchWithProxy(ep.url, controller.signal, CORS_PROXIES)
          const localAfter = Date.now()
          const localMid = (localBefore + localAfter) / 2

          if (!resp.ok) { clearTimeout(timeoutId); continue }

          if (ep.type === 'header') {
            const dateHeader = resp.headers.get('Date')
            if (dateHeader) {
              const serverTime = new Date(dateHeader).getTime()
              const offset = serverTime - localMid
              console.log(`[${source}] Header offset: ${offset.toFixed(1)}ms`)
              clearTimeout(timeoutId)
              return offset
            }
          } else {
            try {
              const json = await resp.clone().json()
              let serverTime: number | null = null

              if (ep.field) {
                const v = getNestedValue(json, ep.field)
                serverTime = parseServerTime(v)
              }
              if (serverTime === null) {
                if (json?.data?.t) serverTime = parseServerTime(json.data.t)
                else if (json?.t) serverTime = parseServerTime(json.t)
                else if (json?.timestamp) serverTime = parseServerTime(json.timestamp)
                else if (typeof json === 'number') serverTime = parseServerTime(json)
              }

              if (serverTime !== null && !isNaN(serverTime)) {
                const offset = serverTime - localMid
                console.log(`[${source}] JSON offset: ${offset.toFixed(1)}ms, server=${new Date(serverTime).toISOString()}`)
                clearTimeout(timeoutId)
                return offset
              }
            } catch { /* not JSON, try next endpoint */ }
          }
        } finally {
          clearTimeout(timeoutId)
        }
      } catch (e) {
        if ((e as Error).name === 'AbortError') {
          console.warn(`[${source}] ${ep.url} timeout, trying next...`)
        } else {
          console.warn(`[${source}] ${ep.url} failed:`, e)
        }
      }
    }

    throw new Error(`无法连接 ${source} 时间服务器`)
  }

  async function doSync() {
    syncStatus.value = 'syncing'
    
    // 触发 Live Update - 同步中
    liveUpdateManager.showTimeSyncing(timeSource.value)
    
    try {
      offsetMs.value = await syncTime(timeSource.value)
      syncStatus.value = 'success'
      lastSyncTime.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
      
      // 触发 Live Update - 同步成功
      liveUpdateManager.showTimeSyncSuccess(timeSource.value, offsetMs.value)
      
      setTimeout(() => { if (syncStatus.value === 'success') syncStatus.value = 'idle' }, 3000)
    } catch {
      syncStatus.value = 'error'
      offsetMs.value = 0
      
      // 触发 Live Update - 同步失败
      liveUpdateManager.showTimeSyncFailed(timeSource.value)
      
      setTimeout(() => { if (syncStatus.value === 'error') syncStatus.value = 'idle' }, 5000)
    }
  }

  function selectSource(source: TimeSource) {
    timeSource.value = source
    doSync()
  }

  return {
    timeSource,
    offsetMs,
    syncStatus,
    lastSyncTime,
    currentSourceLabel,
    themeColor,
    diffDisplay,
    doSync,
    selectSource,
  }
}
