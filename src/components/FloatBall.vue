<script setup lang="ts">
import { computed } from 'vue'
import type { TimeSource, SyncStatus } from '../types'

const props = defineProps<{
  timeSource: TimeSource
  displayTime: string
  displayDate: string
  syncStatus: SyncStatus
  isDarkMode: boolean
  position: { x: number; y: number }
  dragHandlers?: {
    onMouseDown: (e: MouseEvent) => void
    onTouchStart: (e: TouchEvent) => void
    onTouchMove: (e: TouchEvent) => void
    onTouchEnd: () => void
  }
}>()

const emit = defineEmits<{
  click: []
  dragStart: []
}>()

const themeBg = computed(() => {
  if (props.timeSource === 'taobao') {
    return props.isDarkMode ? 'rgba(255,80,0,0.92)' : 'rgba(255,80,0,0.95)'
  }
  if (props.timeSource === 'meituan') {
    return props.isDarkMode ? 'rgba(255,209,0,0.92)' : 'rgba(255,209,0,0.95)'
  }
  return props.isDarkMode ? 'rgba(74,144,226,0.92)' : 'rgba(74,144,226,0.95)'
})

const themeColor = computed(() => {
  if (props.timeSource === 'taobao') return '#FF5000'
  if (props.timeSource === 'meituan') return '#FFD100'
  return '#4A90E2'
})

const sourceLabel = computed(() => {
  if (props.timeSource === 'taobao') return '淘'
  if (props.timeSource === 'meituan') return '团'
  return '本'
})
</script>

<template>
  <div
    class="float-ball"
    :class="{ 'is-dark': isDarkMode }"
    :style="{
      left: position.x + 'px',
      top: position.y + 'px',
      background: themeBg,
      boxShadow: `0 4px 20px rgba(0,0,0,.3),0 0 0 2px ${themeColor}44`
    }"
    @mousedown="dragHandlers ? dragHandlers.onMouseDown : $emit('dragStart')"
    @touchstart.prevent="dragHandlers ? dragHandlers.onTouchStart : $emit('dragStart')"
    @touchmove.prevent="dragHandlers?.onTouchMove"
    @touchend.prevent="dragHandlers?.onTouchEnd"
    @click="$emit('click')"
  >
    <div class="float-time">{{ displayTime }}</div>
    <div class="float-date">{{ displayDate }}</div>
    <div class="float-src" :style="{ color: themeColor }">
      {{ sourceLabel }}
    </div>
    <div class="sync-dot" :class="syncStatus">
      <span v-if="syncStatus === 'syncing'" class="dot-spin"></span>
    </div>
  </div>
</template>

<style scoped>
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

.float-ball.is-dark .float-time { color: #e8e8e8; text-shadow: 0 0 8px rgba(255,255,255,.25), 0 1px 3px rgba(0,0,0,.5); }
.float-ball.is-dark .float-date { color: rgba(255,255,255,.6); }
.float-ball.is-dark .float-src { opacity: .7; }
.float-ball:not(.is-dark) .float-time { color: #fff; text-shadow: 0 1px 3px rgba(0,0,0,.35); }
.float-ball:not(.is-dark) .float-date { color: rgba(255,255,255,.8); }

.float-time { font-size: 22px; font-weight: 700; letter-spacing: 1px; font-variant-numeric: tabular-nums; line-height: 1.2; }
.float-date { font-size: 11px; margin-top: 1px; font-variant-numeric: tabular-nums; }
.float-src { font-size: 11px; font-weight: 600; margin-top: 3px; opacity: .9; }

.sync-dot { position: absolute; top: 6px; right: 6px; width: 7px; height: 7px; border-radius: 50%; background: #ccc; }
.sync-dot.success { background: #52c41a; }
.sync-dot.error   { background: #ff4d4f; }
.sync-dot.syncing { background: #1890ff; }
.dot-spin { display: block; width: 7px; height: 7px; border-radius: 50%; border: 2px solid #1890ff; border-top-color: transparent; animation: spin .8s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
</style>
