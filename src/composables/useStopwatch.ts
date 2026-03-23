import { ref, computed, onUnmounted } from 'vue'
import { formatMs } from '../utils/time'

export function useStopwatch(getOffsetMs: () => number) {
  const stopwatchActive = ref(false)
  const stopwatchMs = ref(0)
  const stopwatchStart = ref(0)
  let stopwatchTimer: ReturnType<typeof setInterval> | null = null

  function toggleStopwatch() {
    if (stopwatchActive.value) {
      stopwatchActive.value = false
      if (stopwatchTimer) {
        clearInterval(stopwatchTimer)
        stopwatchTimer = null
      }
    } else {
      stopwatchActive.value = true
      stopwatchStart.value = Date.now() + getOffsetMs()
      if (stopwatchTimer) {
        clearInterval(stopwatchTimer)
        stopwatchTimer = null
      }
      stopwatchTimer = setInterval(() => {
        stopwatchMs.value = Date.now() + getOffsetMs() - stopwatchStart.value
      }, 16)
    }
  }

  function resetStopwatch() {
    stopwatchMs.value = 0
    stopwatchStart.value = Date.now() + getOffsetMs()
  }

  onUnmounted(() => {
    if (stopwatchTimer) {
      clearInterval(stopwatchTimer)
      stopwatchTimer = null
    }
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
