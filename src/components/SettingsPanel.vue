<script setup lang="ts">
import type { TimeSource, ThemeMode, SyncStatus } from '../types'

const props = defineProps<{
  timeSource: TimeSource
  themeMode: ThemeMode
  syncStatus: SyncStatus
  currentSourceLabel: string
  diffDisplay: string
  lastSyncTime: string
  isDarkMode: boolean
  isOpen: boolean
  position: { x: number; y: number }
}>()

const emit = defineEmits<{
  close: []
  selectSource: [source: TimeSource]
  setTheme: [mode: ThemeMode]
  sync: []
  dragStart: [e: MouseEvent]
  dragMove: [e: MouseEvent]
  dragEnd: []
}>()

function onMouseDown(e: MouseEvent) {
  const target = e.target as HTMLElement
  if (target.closest('.close-btn, .source-item, .sync-btn, .stopwatch-btns button, .theme-btn')) return
  emit('dragStart', e)
}
</script>

<template>
  <Transition name="panel">
    <div
      v-if="isOpen"
      class="settings-panel"
      :class="{ 'is-dark': isDarkMode }"
      :style="{ left: position.x + 'px', top: position.y + 'px' }"
      @mousedown.stop="onMouseDown"
      @mousemove.stop="$emit('dragMove', $event)"
      @mouseup.stop="$emit('dragEnd')"
      @mouseleave.stop="$emit('dragEnd')"
    >
      <div class="panel-header">
        <span>⚙️ 时间校准 v1.2.1</span>
        <button class="close-btn" @click="$emit('close')">✕</button>
      </div>

      <!-- 主题切换 -->
      <div class="section-title">🎨 外观主题</div>
      <div class="theme-switcher">
        <button
          class="theme-btn"
          :class="{ active: themeMode === 'auto' }"
          @click="$emit('setTheme', 'auto')"
        >🔄 跟随系统</button>
        <button
          class="theme-btn"
          :class="{ active: themeMode === 'light' }"
          @click="$emit('setTheme', 'light')"
        >☀️ 日间</button>
        <button
          class="theme-btn"
          :class="{ active: themeMode === 'dark' }"
          @click="$emit('setTheme', 'dark')"
        >🌙 夜间</button>
      </div>

      <div class="section-title">时间源</div>
      <div class="source-list">
        <div
          class="source-item taobao"
          :class="{ active: timeSource === 'taobao' }"
          @click="$emit('selectSource', 'taobao')"
        >
          <div class="source-icon">🐱</div>
          <div class="source-info">
            <div class="source-name">淘宝时间</div>
            <div class="source-desc">阿里系 API，精度 ±50ms</div>
          </div>
          <div v-if="timeSource === 'taobao'" class="check">✓</div>
        </div>
        <div
          class="source-item meituan"
          :class="{ active: timeSource === 'meituan' }"
          @click="$emit('selectSource', 'meituan')"
        >
          <div class="source-icon">🐸</div>
          <div class="source-info">
            <div class="source-name">美团时间</div>
            <div class="source-desc">美团 API，精度 ±50ms</div>
          </div>
          <div v-if="timeSource === 'meituan'" class="check">✓</div>
        </div>
        <div
          class="source-item local"
          :class="{ active: timeSource === 'local' }"
          @click="$emit('selectSource', 'local')"
        >
          <div class="source-icon">📱</div>
          <div class="source-info">
            <div class="source-name">本地时间</div>
            <div class="source-desc">手机系统时间</div>
          </div>
          <div v-if="timeSource === 'local'" class="check">✓</div>
        </div>
      </div>

      <div class="status-bar">
        <div class="status-item">
          <span class="status-label">当前</span>
          <span class="status-value" :style="{ color: timeSource === 'taobao' ? '#FF5000' : timeSource === 'meituan' ? '#FFD100' : '#4A90E2' }">{{ currentSourceLabel }}</span>
        </div>
        <div class="status-item">
          <span class="status-label">偏差</span>
          <span class="status-value" :class="{ warn: diffDisplay !== '±0ms' && diffDisplay.includes('ms') && parseInt(diffDisplay.replace(/[^0-9]/g, '')) > 500 }">{{ diffDisplay }}</span>
        </div>
        <div class="status-item">
          <span class="status-label">同步</span>
          <span class="status-value sync-value">{{ lastSyncTime || '--:--:--' }}</span>
        </div>
      </div>

      <button class="sync-btn" :disabled="syncStatus === 'syncing'" @click="$emit('sync')">
        {{ syncStatus === 'syncing' ? '同步中…' : '🔄 手动同步' }}
      </button>

      <slot name="extra" />
    </div>
  </Transition>
</template>

<style scoped>
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
.settings-panel.is-dark .status-value { color: #fff; }
.settings-panel.is-dark .source-desc,
.settings-panel.is-dark .status-label { color: rgba(255,255,255,.45); }
.settings-panel.is-dark .theme-btn { background: rgba(255,255,255,.06); border-color: rgba(255,255,255,.1); color: rgba(255,255,255,.8); }
.settings-panel.is-dark .theme-btn:hover { background: rgba(255,255,255,.12); }
.settings-panel.is-dark .theme-btn.active { border-color: #6B9FFF; background: rgba(107,159,255,.2); color: #fff; }
.settings-panel.is-dark .source-item { background: rgba(255,255,255,.05); }
.settings-panel.is-dark .source-item:hover { background: rgba(255,255,255,.1); }
.settings-panel.is-dark .source-item.taobao.active  { border-color: #FF6B3D; background: rgba(255,107,61,.15); }
.settings-panel.is-dark .source-item.meituan.active { border-color: #FFD54F; background: rgba(255,213,79,.15); }
.settings-panel.is-dark .source-item.local.active   { border-color: #64B5F6; background: rgba(100,181,246,.15); }
.settings-panel.is-dark .status-bar { background: rgba(255,255,255,.05); }
.settings-panel.is-dark .status-item { border-right-color: rgba(255,255,255,.08); }
.settings-panel.is-dark .sync-btn { background: rgba(255,255,255,.1); color: #fff; }
.settings-panel.is-dark .sync-btn:hover:not(:disabled) { background: rgba(255,255,255,.2); }
.settings-panel.is-dark .close-btn { background: rgba(255,255,255,.1); color: rgba(255,255,255,.6); }
.settings-panel.is-dark .close-btn:hover { background: rgba(255,255,255,.2); color: #fff; }

/* 浅色主题 */
.settings-panel:not(.is-dark) {
  background: rgba(255,255,255,.97);
  box-shadow: 0 12px 48px rgba(0,0,0,.12), 0 0 0 1px rgba(0,0,0,.06);
}
.settings-panel:not(.is-dark) .panel-header,
.settings-panel:not(.is-dark) .section-title,
.settings-panel:not(.is-dark) .source-name,
.settings-panel:not(.is-dark) .status-value { color: #1a1a2e; }
.settings-panel:not(.is-dark) .source-desc,
.settings-panel:not(.is-dark) .status-label { color: rgba(0,0,0,.5); }
.settings-panel:not(.is-dark) .source-item { background: rgba(0,0,0,.03); }
.settings-panel:not(.is-dark) .source-item:hover { background: rgba(0,0,0,.06); }
.settings-panel:not(.is-dark) .status-bar { background: rgba(0,0,0,.03); }
.settings-panel:not(.is-dark) .status-item { border-right-color: rgba(0,0,0,.06); }
.settings-panel:not(.is-dark) .sync-btn { background: rgba(0,0,0,.06); color: #1a1a2e; }
.settings-panel:not(.is-dark) .close-btn { background: rgba(0,0,0,.06); color: #666; }
.settings-panel:not(.is-dark) .theme-btn { background: rgba(0,0,0,.03); border-color: rgba(0,0,0,.08); color: #1a1a2e; }
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

.theme-switcher { display: flex; gap: 8px; margin-bottom: 12px; }
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
.theme-btn:hover { background: rgba(255,255,255,.08); }
.theme-btn.active { border-color: #4A90E2; background: rgba(74,144,226,.15); }

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

.panel-enter-active, .panel-leave-active { transition: opacity .2s, transform .2s; }
.panel-enter-from, .panel-leave-to { opacity: 0; transform: scale(.92) translateY(8px); }
</style>
