<script setup lang="ts">
const props = defineProps<{
  formattedTime: string
  isActive: boolean
  isDarkMode: boolean
}>()

const emit = defineEmits<{
  toggle: []
  reset: []
}>()
</script>

<template>
  <div class="stopwatch" :class="{ 'is-dark': isDarkMode }">
    <div class="stopwatch-title">秒表</div>
    <div class="stopwatch-display">{{ formattedTime }}</div>
    <div class="stopwatch-btns">
      <button @click="$emit('toggle')">{{ isActive ? '⏸' : '▶' }}</button>
      <button @click="$emit('reset')">↺</button>
    </div>
  </div>
</template>

<style scoped>
.stopwatch { margin-top: 10px; padding: 10px; background: rgba(128,128,128,.06); border-radius: 10px; border: 1px solid rgba(128,128,128,.1); }
.stopwatch.is-dark { background: rgba(255,255,255,.05); border-color: rgba(255,255,255,.08); }
.stopwatch-title { font-size: 11px; margin-bottom: 4px; color: rgba(0,0,0,.5); }
.stopwatch.is-dark .stopwatch-title { color: rgba(255,255,255,.45); }
.stopwatch-display { font-size: 28px; font-weight: 700; font-variant-numeric: tabular-nums; text-align: center; letter-spacing: 2px; font-family: 'Courier New', monospace; }
.stopwatch.is-dark .stopwatch-display { color: #fff; }
.stopwatch:not(.is-dark) .stopwatch-display { color: #1a1a2e; }
.stopwatch-btns { display: flex; gap: 8px; margin-top: 6px; }
.stopwatch-btns button { flex: 1; padding: 6px; border-radius: 8px; border: 1px solid rgba(255,255,255,.15); background: rgba(255,255,255,.07); font-size: 16px; cursor: pointer; }
.stopwatch.is-dark .stopwatch-btns button { background: rgba(255,255,255,.08); border-color: rgba(255,255,255,.12); color: #fff; }
.stopwatch:not(.is-dark) .stopwatch-btns button { background: rgba(0,0,0,.05); border-color: rgba(0,0,0,.08); color: #1a1a2e; }
.stopwatch-btns button:hover { background: rgba(255,255,255,.15); }
.stopwatch:not(.is-dark) .stopwatch-btns button:hover { background: rgba(0,0,0,.1); }
</style>
