<script setup lang="ts">
import { ref, onMounted, onUnmounted, computed, watch } from 'vue'

type TimeSource = 'taobao' | 'meituan' | 'local'
type ThemeMode = 'auto' | 'light' | 'dark'

const timeSource = ref<TimeSource>('taobao')
const displayTime = ref('00:00:00')
const displayDate = ref('----/--/--')
const syncStatus = ref<'idle' | 'syncing' | 'success' | 'error'>('idle')
const offsetMs = ref(0)
const lastSyncTime = ref('')
const diffDisplay = ref('±0ms')
const isSettingsOpen = ref(false)
const isDragging = ref(false)
const dragStartX = ref(0)
const dragStartY = ref(0)
const floatX = ref(window.innerWidth - 120)
const floatY = ref(120)
const panelX = ref(Math.max(10, window.innerWidth / 2 - 150))
const panelY = ref(Math.max(10, window.innerHeight / 2 - 250))
const isPanelDragging = ref(false)
const panelDragStartX = ref(0)
const panelDragStartY = ref(0)
const stopwatchActive = ref(false)
const stopwatchMs = ref(0)
let stopwatchTimer: ReturnType<typeof setInterval> | null = null
const stopwatchStart = ref(0)
let tickTimer: ReturnType<typeof setInterval> | null = null
let syncTimer: ReturnType<typeof setInterval> | null = null

// ========== 日夜间模式 ==========
const themeMode = ref<ThemeMode>('auto')
const systemIsDark = ref(window.matchMedia('(prefers-color-scheme: dark)').matches)

const isDarkMode = computed(() => {
  if (themeMode.value === 'auto') {
    return systemIsDark.value
  }
  return themeMode.value === 'dark'
})

watch(isDarkMode, () => {
  applyTheme()
}, { immediate: true })

let darkModeQuery: MediaQueryList | null = null

function setupThemeListener() {
  darkModeQuery = window.matchMedia('(prefers-color-scheme: dark)')
  systemIsDark.value = darkModeQuery.matches
  darkModeQuery.addEventListener('change', (e) => {
    systemIsDark.value = e.matches
  })
}

function applyTheme() {
  const root = document.documentElement
  if (isDarkMode.value) {
    root.classList.add('dark-theme')
    root.classList.remove('light-theme')
  } else {
    root.classList.add('light-theme')
    root.classList.remove('dark-theme')
  }
}

function setThemeMode(mode: ThemeMode) {
  themeMode.value = mode
  localStorage.setItem('floattime-theme', mode)
}

// ========== 原有功能 ==========

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

const themeBg = computed(() => {
  if (timeSource.value === 'taobao') return isDarkMode.value ? 'rgba(255,80,0,0.92)' : 'rgba(255,80,0,0.95)'
  if (timeSource.value === 'meituan') return isDarkMode.value ? 'rgba(255,209,0,0.92)' : 'rgba(255,209,0,0.95)'
  return isDarkMode.value ? 'rgba(74,144,226,0.92)' : 'rgba(74,144,226,0.95)'
})

async function syncTime(source: TimeSource): Promise<number> {
  if (source === 'local') return 0

  const endpoints: Record<string, { url: string; type: 'header' | 'json' }[]> = {
    taobao: [
      { url: 'https://api.m.taobao.com/rest/api3.do?api=mtop.common.getTimestamp', type: 'json' },
      { url: 'https://api.taobao.com/router/rest?app_key=&v=&sign=', type: 'header' },
    ],
    meituan: [
      { url: 'https://api.meituan.com/nationalTimestamp', type: 'json' },
      { url: 'https://api-ssl.meituan.com/nationalTimestamp', type: 'json' },
    ],
  }

  const candidates = endpoints[source] || []

  for (const ep of candidates) {
    try {
      const localBefore = Date.now()
      const resp = await fetch(ep.url, { method: 'GET', cache: 'no-cache' })
      const localAfter = Date.now()
      const localMid = (localBefore + localAfter) / 2

      if (ep.type === 'header') {
        const dateHeader = resp.headers.get('Date')
        if (dateHeader) {
          const serverTime = new Date(dateHeader).getTime()
          const offset = serverTime - localMid
          console.log(`[${source}] Header offset: ${offset.toFixed(1)}ms`)
          return offset
        }
      } else {
        try {
          const json = await resp.clone().json()
          let serverTime: number | null = null
          if (json?.data?.t) serverTime = parseInt(json.data.t)
          else if (json?.t) serverTime = parseInt(json.t) * 1000
          else if (json?.timestamp) serverTime = parseInt(json.timestamp) * (String(json.timestamp).length === 10 ? 1000 : 1)
          else if (typeof json === 'number') serverTime = json * (String(json).length === 10 ? 1000 : 1)

          if (serverTime !== null && !isNaN(serverTime)) {
            const offset = serverTime - localMid
            console.log(`[${source}] JSON offset: ${offset.toFixed(1)}ms, server=${new Date(serverTime).toISOString()}`)
            return offset
          }
        } catch { /* not JSON */ }
      }
    } catch (e) {
      console.warn(`[${source}] ${ep.url} failed:`, e)
    }
  }

  throw new Error(`无法连接 ${source} 时间服务器`)
}

function updateDisplay() {
  const now = Date.now() + offsetMs.value
  const d = new Date(now)
  displayTime.value = `${String(d.getHours()).padStart(2,'0')}:${String(d.getMinutes()).padStart(2,'0')}:${String(d.getSeconds()).padStart(2,'0')}`
  displayDate.value = `${d.getFullYear()}/${String(d.getMonth()+1).padStart(2,'0')}/${String(d.getDate()).padStart(2,'0')}`
  const diff = offsetMs.value
  const absD = Math.abs(diff)
  diffDisplay.value = absD < 50 ? '±0ms' : diff >= 0 ? `+${absD}ms` : `-${absD}ms`
}

async function doSync() {
  syncStatus.value = 'syncing'
  try {
    offsetMs.value = await syncTime(timeSource.value)
    syncStatus.value = 'success'
    lastSyncTime.value = new Date().toLocaleTimeString('zh-CN', { hour12: false })
  } catch {
    syncStatus.value = 'error'
    offsetMs.value = 0
  }
  updateDisplay()
}

function startTick() {
  if (tickTimer) clearInterval(tickTimer)
  tickTimer = setInterval(updateDisplay, isDragging.value ? 16 : 1000)
}

function startSyncInterval() {
  if (syncTimer) clearInterval(syncTimer)
  syncTimer = setInterval(doSync, 5 * 60 * 1000)
}

// Float drag
function onFloatTouchStart(e: TouchEvent) {
  isDragging.value = true
  const t = e.touches[0]
  dragStartX.value = t.clientX - floatX.value
  dragStartY.value = t.clientY - floatY.value
  clearInterval(tickTimer!)
  tickTimer = setInterval(updateDisplay, 16)
}
function onFloatTouchMove(e: TouchEvent) {
  if (!isDragging.value) return
  e.preventDefault()
  floatX.value = Math.max(0, Math.min(window.innerWidth - 110, e.touches[0].clientX - dragStartX.value))
  floatY.value = Math.max(0, Math.min(window.innerHeight - 60, e.touches[0].clientY - dragStartY.value))
}
function onFloatTouchEnd() { isDragging.value = false; startTick() }
function onFloatMouseDown(e: MouseEvent) {
  isDragging.value = true
  dragStartX.value = e.clientX - floatX.value
  dragStartY.value = e.clientY - floatY.value
  clearInterval(tickTimer!)
  tickTimer = setInterval(updateDisplay, 16)
}
function onFloatMouseMove(e: MouseEvent) {
  if (!isDragging.value) return
  floatX.value = Math.max(0, Math.min(window.innerWidth - 110, e.clientX - dragStartX.value))
  floatY.value = Math.max(0, Math.min(window.innerHeight - 60, e.clientY - dragStartY.value))
}
function onFloatMouseUp() { isDragging.value = false; startTick() }

// Panel drag
function onPanelMouseDown(e: MouseEvent) {
  if ((e.target as HTMLElement).closest('.close-btn, .source-item, .sync-btn, .stopwatch-btns button, .theme-btn')) return
  isPanelDragging.value = true
  panelDragStartX.value = e.clientX - panelX.value
  panelDragStartY.value = e.clientY - panelY.value
}
function onPanelMouseMove(e: MouseEvent) {
  if (!isPanelDragging.value) return
  panelX.value = Math.max(0, Math.min(window.innerWidth - 310, e.clientX - panelDragStartX.value))
  panelY.value = Math.max(0, Math.min(window.innerHeight - 500, e.clientY - panelDragStartY.value))
}
function onPanelMouseUp() { isPanelDragging.value = false }

async function selectSource(src: TimeSource) {
  timeSource.value = src
  isSettingsOpen.value = false
  await doSync()
}

function toggleStopwatch() {
  if (stopwatchActive.value) {
    stopwatchActive.value = false
    if (stopwatchTimer) clearInterval(stopwatchTimer)
  } else {
    stopwatchActive.value = true
    stopwatchStart.value = Date.now() + offsetMs.value
    stopwatchTimer = setInterval(() => {
      stopwatchMs.value = Date.now() + offsetMs.value - stopwatchStart.value
    }, 16)
  }
}
function resetStopwatch() { stopwatchMs.value = 0; stopwatchStart.value = Date.now() + offsetMs.value }
function formatMs(ms: number) {
  const s = Math.floor(ms / 1000)
  return `${String(Math.floor(s/60)).padStart(2,'0')}:${String(s%60).padStart(2,'0')}.${String(Math.floor((ms%1000)/10)).padStart(2,'0')}`
}

onMounted(async () => {
  // 恢复主题设置
  const savedTheme = localStorage.getItem('floattime-theme') as ThemeMode | null
  if (savedTheme) {
    themeMode.value = savedTheme
  }
  setupThemeListener()
  applyTheme()
  
  await doSync()
  startTick()
  startSyncInterval()
})
onUnmounted(() => {
  clearInterval(tickTimer!)
  clearInterval(syncTimer!)
  clearInterval(stopwatchTimer!)
  if (darkModeQuery) {
    darkModeQuery.removeEventListener('change', () => {})
  }
})
</script>

<template>
  <div class="root" :class="{ 'is-dark': isDarkMode }" @mousemove="onFloatMouseMove" @mouseup="onFloatMouseUp" @mouseleave="onFloatMouseUp">
    <!-- Floating Ball -->
    <div
      class="float-ball"
      :style="{ left: floatX+'px', top: floatY+'px', background: themeBg, boxShadow: `0 4px 20px rgba(0,0,0,.3),0 0 0 2px ${themeColor}44` }"
      @mousedown="onFloatMouseDown"
      @touchstart.prevent="onFloatTouchStart"
      @touchmove.prevent="onFloatTouchMove"
      @touchend.prevent="onFloatTouchEnd"
      @click="isSettingsOpen = !isSettingsOpen"
    >
      <div class="float-time">{{ displayTime }}</div>
      <div class="float-date">{{ displayDate }}</div>
      <div class="float-src" :style="{ color: themeColor }">
        {{ timeSource === 'taobao' ? '淘' : timeSource === 'meituan' ? '团' : '本' }}
      </div>
      <div class="sync-dot" :class="syncStatus">
        <span v-if="syncStatus === 'syncing'" class="dot-spin"></span>
      </div>
    </div>

    <!-- Settings Panel -->
    <Transition name="panel">
      <div
        v-if="isSettingsOpen"
        class="settings-panel"
        :class="{ 'is-dark': isDarkMode }"
        :style="{ left: panelX+'px', top: panelY+'px' }"
        @mousedown.stop="onPanelMouseDown"
        @mousemove.stop="onPanelMouseMove"
        @mouseup.stop="onPanelMouseUp"
        @mouseleave.stop="onPanelMouseUp"
      >
        <div class="panel-header">
          <span>⚙️ 时间校准 v1.2.0</span>
          <button class="close-btn" @click="isSettingsOpen = false">✕</button>
        </div>

        <!-- 主题切换 -->
        <div class="section-title">🎨 外观主题</div>
        <div class="theme-switcher">
          <button 
            class="theme-btn" 
            :class="{ active: themeMode === 'auto' }"
            @click="setThemeMode('auto')"
          >🔄 跟随系统</button>
          <button 
            class="theme-btn" 
            :class="{ active: themeMode === 'light' }"
            @click="setThemeMode('light')"
          >☀️ 日间</button>
          <button 
            class="theme-btn" 
            :class="{ active: themeMode === 'dark' }"
            @click="setThemeMode('dark')"
          >🌙 夜间</button>
        </div>

        <div class="section-title">时间源</div>
        <div class="source-list">
          <div class="source-item taobao" :class="{ active: timeSource === 'taobao' }" @click="selectSource('taobao')">
            <div class="source-icon">🐱</div>
            <div class="source-info">
              <div class="source-name">淘宝时间</div>
              <div class="source-desc">阿里系 API，精度 ±50ms</div>
            </div>
            <div v-if="timeSource==='taobao'" class="check">✓</div>
          </div>
          <div class="source-item meituan" :class="{ active: timeSource === 'meituan' }" @click="selectSource('meituan')">
            <div class="source-icon">🐸</div>
            <div class="source-info">
              <div class="source-name">美团时间</div>
              <div class="source-desc">美团 API，精度 ±50ms</div>
            </div>
            <div v-if="timeSource==='meituan'" class="check">✓</div>
          </div>
          <div class="source-item local" :class="{ active: timeSource === 'local' }" @click="selectSource('local')">
            <div class="source-icon">📱</div>
            <div class="source-info">
              <div class="source-name">本地时间</div>
              <div class="source-desc">手机系统时间</div>
            </div>
            <div v-if="timeSource==='local'" class="check">✓</div>
          </div>
        </div>

        <div class="status-bar">
          <div class="status-item">
            <span class="status-label">当前</span>
            <span class="status-value" :style="{ color: themeColor }">{{ currentSourceLabel }}</span>
          </div>
          <div class="status-item">
            <span class="status-label">偏差</span>
            <span class="status-value" :class="{ warn: Math.abs(offsetMs) > 500 }">{{ diffDisplay }}</span>
          </div>
          <div class="status-item">
            <span class="status-label">同步</span>
            <span class="status-value sync-value">{{ lastSyncTime || '--:--:--' }}</span>
          </div>
        </div>

        <button class="sync-btn" :disabled="syncStatus==='syncing'" @click="doSync">
          {{ syncStatus==='syncing' ? '同步中…' : '🔄 手动同步' }}
        </button>

        <div class="stopwatch">
          <div class="stopwatch-title">秒表</div>
          <div class="stopwatch-display">{{ formatMs(stopwatchMs) }}</div>
          <div class="stopwatch-btns">
            <button @click="toggleStopwatch">{{ stopwatchActive ? '⏸' : '▶' }}</button>
            <button @click="resetStopwatch">↺</button>
          </div>
        </div>
      </div>
    </Transition>
  </div>
</template>

<style scoped>
.root { position: fixed; inset: 0; pointer-events: none; z-index: 99999; }

.float-ball {
  position: absolute;
  width: 108px;
  padding: 8px 6px 6px;
  border-radius: 14px;
  display: flex;
  flex-direction: column;
  align-items: center;
  cursor: grab;
  user-select: none;
  pointer-events: all;
}
.float-ball:active { cursor: grabbing; }
.float-time { font-size: 22px; font-weight: 700; color: #fff; letter-spacing: 1px; font-variant-numeric: tabular-nums; line-height: 1.2; text-shadow: 0 1px 3px rgba(0,0,0,.35); }
.float-date { font-size: 11px; color: rgba(255,255,255,.8); margin-top: 1px; font-variant-numeric: tabular-nums; }
.float-src { font-size: 11px; font-weight: 600; margin-top: 3px; opacity: .9; }

.sync-dot { position: absolute; top: 6px; right: 6px; width: 7px; height: 7px; border-radius: 50%; background: #ccc; }
.sync-dot.success { background: #52c41a; }
.sync-dot.error   { background: #ff4d4f; }
.sync-dot.syncing { background: #1890ff; }
.dot-spin { display: block; width: 7px; height: 7px; border-radius: 50%; border: 2px solid #1890ff; border-top-color: transparent; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.settings-panel {
  position: absolute;
  width: 300px;
  border-radius: 18px;
  padding: 16px;
  pointer-events: all;
  cursor: default;
  backdrop-filter: blur(24px);
  -webkit-backdrop-filter: blur(24px);
}

/* 深色主题 */
.settings-panel.is-dark {
  background: rgba(14,14,22,.97);
  box-shadow: 0 12px 48px rgba(0,0,0,.65), 0 0 0 1px rgba(255,255,255,.07);
}
.settings-panel.is-dark .panel-header,
.settings-panel.is-dark .section-title,
.settings-panel.is-dark .source-name,
.settings-panel.is-dark .status-value,
.settings-panel.is-dark .stopwatch-display { color: #fff; }
.settings-panel.is-dark .source-desc,
.settings-panel.is-dark .status-label,
.settings-panel.is-dark .stopwatch-title { color: rgba(255,255,255,.45); }

/* 浅色主题 */
.settings-panel:not(.is-dark) {
  background: rgba(255,255,255,.97);
  box-shadow: 0 12px 48px rgba(0,0,0,.12), 0 0 0 1px rgba(0,0,0,.06);
}
.settings-panel:not(.is-dark) .panel-header,
.settings-panel:not(.is-dark) .section-title,
.settings-panel:not(.is-dark) .source-name,
.settings-panel:not(.is-dark) .status-value,
.settings-panel:not(.is-dark) .stopwatch-display { color: #1a1a2e; }
.settings-panel:not(.is-dark) .source-desc,
.settings-panel:not(.is-dark) .status-label,
.settings-panel:not(.is-dark) .stopwatch-title { color: rgba(0,0,0,.5); }
.settings-panel:not(.is-dark) .source-item { background: rgba(0,0,0,.03); }
.settings-panel:not(.is-dark) .source-item:hover { background: rgba(0,0,0,.06); }
.settings-panel:not(.is-dark) .status-bar { background: rgba(0,0,0,.03); }
.settings-panel:not(.is-dark) .status-item { border-right-color: rgba(0,0,0,.06); }
.settings-panel:not(.is-dark) .sync-btn { background: rgba(0,0,0,.06); color: #1a1a2e; }
.settings-panel:not(.is-dark) .stopwatch { background: rgba(0,0,0,.03); border-color: rgba(0,0,0,.06); }
.settings-panel:not(.is-dark) .stopwatch-btns button { background: rgba(0,0,0,.05); border-color: rgba(0,0,0,.08); color: #1a1a2e; }
.settings-panel:not(.is-dark) .close-btn { background: rgba(0,0,0,.06); color: #666; }
.settings-panel:not(.is-dark) .theme-btn { background: rgba(0,0,0,.03); border-color: rgba(0,0,0,.08); }
.settings-panel:not(.is-dark) .theme-btn:hover { background: rgba(0,0,0,.06); }
.settings-panel:not(.is-dark) .theme-btn.active { border-color: #4A90E2; background: rgba(74,144,226,.1); }

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 14px;
  font-weight: 600;
  margin-bottom: 14px;
  cursor: move;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(128,128,128,.15);
}
.close-btn {
  background: rgba(255,255,255,.1);
  border: none;
  color: #aaa;
  width: 24px;
  height: 24px;
  border-radius: 50%;
  cursor: pointer;
  font-size: 12px;
  display: flex;
  align-items: center;
  justify-content: center;
}
.close-btn:hover { background: rgba(255,255,255,.2); color: #fff; }

.section-title { font-size: 11px; text-transform: uppercase; letter-spacing: 1px; margin-bottom: 8px; }

/* 主题切换按钮 */
.theme-switcher {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
}
.theme-btn {
  flex: 1;
  padding: 10px 6px;
  border-radius: 10px;
  border: 2px solid rgba(255,255,255,.08);
  background: rgba(255,255,255,.04);
  font-size: 11px;
  cursor: pointer;
  transition: all 0.15s;
}
.theme-btn:hover {
  background: rgba(255,255,255,.08);
}
.theme-btn.active {
  border-color: #4A90E2;
  background: rgba(74,144,226,.15);
}
.settings-panel:not(.is-dark) .theme-btn { color: #1a1a2e; }
.settings-panel.is-dark .theme-btn { color: #fff; }

.source-list { display: flex; flex-direction: column; gap: 6px; }

.source-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(255,255,255,.05);
  cursor: pointer;
  transition: background .15s;
  border: 2px solid transparent;
}
.source-item:hover { background: rgba(255,255,255,.09); }
.source-item.taobao.active  { border-color: #FF5000; background: rgba(255,80,0,.12); }
.source-item.meituan.active { border-color: #FFD100; background: rgba(255,209,0,.12); }
.source-item.local.active   { border-color: #4A90E2; background: rgba(74,144,226,.12); }

.source-icon { font-size: 24px; flex-shrink: 0; }
.source-info { flex: 1; }
.source-name { font-size: 14px; font-weight: 600; }
.source-desc { font-size: 11px; margin-top: 1px; }
.check { color: #52c41a; font-size: 18px; font-weight: 700; }

.status-bar { display: flex; margin-top: 12px; border-radius: 10px; overflow: hidden; background: rgba(128,128,128,.08); }
.status-item { flex: 1; padding: 7px 8px; text-align: center; border-right: 1px solid rgba(128,128,128,.1); }
.status-item:last-child { border-right: none; }
.status-label { display: block; font-size: 10px; }
.status-value { display: block; font-size: 12px; font-weight: 600; margin-top: 1px; font-variant-numeric: tabular-nums; }
.status-value.warn { color: #ff7875; }
.sync-value { font-size: 11px; }

.sync-btn {
  width: 100%; margin-top: 10px; padding: 10px;
  border-radius: 10px; border: none;
  background: rgba(255,255,255,.12);
  color: #fff; font-size: 13px; font-weight: 600;
  cursor: pointer; transition: background .15s;
}
.sync-btn:hover:not(:disabled) { background: rgba(255,255,255,.22); }
.sync-btn:disabled { opacity: .5; cursor: not-allowed; }

.stopwatch { margin-top: 10px; padding: 10px; background: rgba(128,128,128,.06); border-radius: 10px; border: 1px solid rgba(128,128,128,.1); }
.stopwatch-title { font-size: 11px; margin-bottom: 4px; }
.stopwatch-display { font-size: 28px; font-weight: 700; font-variant-numeric: tabular-nums; text-align: center; letter-spacing: 2px; font-family: 'Courier New', monospace; }
.stopwatch-btns { display: flex; gap: 8px; margin-top: 6px; }
.stopwatch-btns button { flex: 1; padding: 6px; border-radius: 8px; border: 1px solid rgba(255,255,255,.15); background: rgba(255,255,255,.07); color: #fff; font-size: 16px; cursor: pointer; }
.stopwatch-btns button:hover { background: rgba(255,255,255,.15); }

.panel-enter-active, .panel-leave-active { transition: opacity .2s, transform .2s; }
.panel-enter-from, .panel-leave-to { opacity: 0; transform: scale(.92) translateY(8px); }
</style>