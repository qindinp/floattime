import { ref, computed, onUnmounted, watch } from 'vue'
import { formatMs } from '../utils/time'
import { liveUpdateManager } from '../utils/liveUpdate'

export function useStopwatch(getOffsetMs: () => number) {
  const stopwatchActive = ref(false)
  const stopwatchMs = ref(0)
  const stopwatchStart = ref(0)
  let stopwatchTimer: ReturnType<typeof setInterval> | null = null

  function toggleStopwatch() {
    if (stopwatchActive.value) {
      // 暂停
      stopwatchActive.value = false
      if (stopwatchTimer) {
        clearInterval(stopwatchTimer)
        stopwatchTimer = null
      }
      // 触发 Live Update - 暂停
      liveUpdateManager.pauseStopwatch()
    } else {
      // 开始
      stopwatchActive.value = true
      stopwatchStart.value = Date.now() + getOffsetMs()
      if (stopwatchTimer) {
        clearInterval(stopwatchTimer)
        stopwatchTimer = null
      }
      stopwatchTimer = setInterval(() => {
        stopwatchMs.value = Date.now() + getOffsetMs() - stopwatchStart.value
      }, 16)
      // 触发 Live Update - 开始
      liveUpdateManager.startStopwatch()
    }
  }

  function resetStopwatch() {
    stopwatchMs.value = 0
    stopwatchStart.value = Date.now() + getOffsetMs()
    // 触发 Live Update - 停止
    liveUpdateManager.stopStopwatch()
  }

  onUnmounted(() => {
    if (stopwatchTimer) {
      clearInterval(stopwatchTimer)
      stopwatchTimer = null
    }
    // 清理 Live Update
    liveUpdateManager.stopStopwatch()
  })

  const formattedTime = computed(() => formatMs(stopwatchMs.value))

  return {
    stopwatchActive,
    stopwatchMs,
    formattedTime,
    toggleStopwatch,
    resetStopwatch,
  }
}
