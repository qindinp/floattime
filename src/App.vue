<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import FloatBall from './components/FloatBall.vue'
import SettingsPanel from './components/SettingsPanel.vue'
import Stopwatch from './components/Stopwatch.vue'
import { useTheme } from './composables/useTheme'
import { useTimeSync } from './composables/useTimeSync'
import { useDrag } from './composables/useDrag'
import { useStopwatch } from './composables/useStopwatch'
import { formatTime, formatDate } from './utils/time'
import { STORAGE_KEYS } from './utils/config'

// 主题管理
const { themeMode, isDarkMode, setThemeMode } = useTheme()

// 时间同步
const {
  timeSource,
  offsetMs,
  syncStatus,
  lastSyncTime,
  currentSourceLabel,
  themeColor,
  diffDisplay,
  doSync,
  selectSource,
} = useTimeSync()

// 显示时间
const displayTime = ref('00:00:00')
const displayDate = ref('----/--/--')

function updateDisplay() {
  const now = Date.now() + offsetMs.value
  const d = new Date(now)
  displayTime.value = formatTime(d)
  displayDate.value = formatDate(d)
}

// 定时器
let tickTimer: ReturnType<typeof setInterval> | null = null
let syncTimer: ReturnType<typeof setInterval> | null = null

function startTick() {
  if (tickTimer) clearInterval(tickTimer)
  tickTimer = setInterval(updateDisplay, 1000)
}

function startSyncInterval() {
  if (syncTimer) clearInterval(syncTimer)
  syncTimer = setInterval(doSync, 5 * 60 * 1000)
}

// 悬浮球拖拽
const ballDrag = useDrag({
  storageKey: STORAGE_KEYS.BALL_POSITION,
  defaultPos: { x: window.innerWidth - 120, y: 120 },
  bounds: { maxX: window.innerWidth - 110, maxY: window.innerHeight - 60 },
  onDragStart: () => {
    if (tickTimer) clearInterval(tickTimer)
    tickTimer = setInterval(updateDisplay, 16)
  },
  onDragEnd: () => {
    startTick()
  },
})

// 面板拖拽
const isPanelDragging = ref(false)
const panelDragStart = ref({ x: 0, y: 0 })
const panelPosition = ref({ x: Math.max(10, window.innerWidth / 2 - 150), y: Math.max(10, window.innerHeight / 2 - 250) })

function loadPanelPosition() {
  const saved = localStorage.getItem(STORAGE_KEYS.PANEL_POSITION)
  if (saved) {
    try {
      const p = JSON.parse(saved)
      if (typeof p.x === 'number' && typeof p.y === 'number') {
        panelPosition.value = {
          x: Math.max(0, Math.min(window.innerWidth - 310, p.x)),
          y: Math.max(0, Math.min(window.innerHeight - 500, p.y)),
        }
      }
    } catch {}
  }
}

function savePanelPosition() {
  localStorage.setItem(STORAGE_KEYS.PANEL_POSITION, JSON.stringify(panelPosition.value))
}

function onPanelDragStart(e: MouseEvent) {
  isPanelDragging.value = true
  panelDragStart.value = {
    x: e.clientX - panelPosition.value.x,
    y: e.clientY - panelPosition.value.y,
  }
}

function onPanelDragMove(e: MouseEvent) {
  if (!isPanelDragging.value) return
  panelPosition.value = {
    x: Math.max(0, Math.min(window.innerWidth - 310, e.clientX - panelDragStart.value.x)),
    y: Math.max(0, Math.min(window.innerHeight - 500, e.clientY - panelDragStart.value.y)),
  }
}

function onPanelDragEnd() {
  if (!isPanelDragging.value) return
  isPanelDragging.value = false
  savePanelPosition()
}

function onWindowResize() {
  ballDrag.position.value = {
    x: Math.max(0, Math.min(window.innerWidth - 110, ballDrag.position.value.x)),
    y: Math.max(0, Math.min(window.innerHeight - 60, ballDrag.position.value.y)),
  }
  panelPosition.value = {
    x: Math.max(0, Math.min(window.innerWidth - 310, panelPosition.value.x)),
    y: Math.max(0, Math.min(window.innerHeight - 500, panelPosition.value.y)),
  }
}

// 设置面板开关
const isSettingsOpen = ref(false)

function toggleSettings() {
  isSettingsOpen.value = !isSettingsOpen.value
}

async function handleSelectSource(source: Parameters<typeof selectSource>[0]) {
  isSettingsOpen.value = false
  await selectSource(source)
}

// 秒表
const { stopwatchActive, formattedTime, toggleStopwatch, resetStopwatch } = useStopwatch(() => offsetMs.value)

// 生命周期
onMounted(async () => {
  loadPanelPosition()
  window.addEventListener('resize', onWindowResize)

  await doSync()
  startTick()
  startSyncInterval()
})

onUnmounted(() => {
  if (tickTimer) clearInterval(tickTimer)
  if (syncTimer) clearInterval(syncTimer)
  window.removeEventListener('resize', onWindowResize)
})
</script>

<template>
  <div
    class="root"
    :class="{ 'is-dark': isDarkMode }"
    @mousemove="ballDrag.handlers.onMouseMove"
    @mouseup="ballDrag.handlers.onMouseUp"
    @mouseleave="ballDrag.handlers.onMouseUp"
  >
    <!-- 悬浮球 -->
    <FloatBall
      :time-source="timeSource"
      :display-time="displayTime"
      :display-date="displayDate"
      :sync-status="syncStatus"
      :is-dark-mode="isDarkMode"
      :position="ballDrag.position.value"
      :drag-handlers="ballDrag.handlers"
      @click="toggleSettings"
    />

    <!-- 设置面板 -->
    <SettingsPanel
      :is-open="isSettingsOpen"
      :time-source="timeSource"
      :theme-mode="themeMode"
      :sync-status="syncStatus"
      :current-source-label="currentSourceLabel"
      :diff-display="diffDisplay"
      :last-sync-time="lastSyncTime"
      :is-dark-mode="isDarkMode"
      :position="panelPosition"
      @close="isSettingsOpen = false"
      @select-source="handleSelectSource"
      @set-theme="setThemeMode"
      @sync="doSync"
      @drag-start="onPanelDragStart"
      @drag-move="onPanelDragMove"
      @drag-end="onPanelDragEnd"
    >
      <template #extra>
        <Stopwatch
          :formatted-time="formattedTime"
          :is-active="stopwatchActive"
          :is-dark-mode="isDarkMode"
          @toggle="toggleStopwatch"
          @reset="resetStopwatch"
        />
      </template>
    </SettingsPanel>
  </div>
</template>

<style scoped>
.root {
  position: fixed;
  inset: 0;
  pointer-events: none;
  z-index: 99999;
}
</style>
