import { ref, onMounted, onUnmounted } from 'vue'
import type { Position } from '../types'
import { loadPosition, savePosition } from '../utils/storage'

interface DragOptions {
  storageKey: string
  defaultPos: Position
  bounds: { maxX: number; maxY: number }
  onDragStart?: () => void
  onDragEnd?: (pos: Position) => void
}

export function useDrag(options: DragOptions) {
  const isDragging = ref(false)
  const position = ref<Position>(options.defaultPos)
  const dragStartX = ref(0)
  const dragStartY = ref(0)

  function loadSavedPosition() {
    position.value = loadPosition(options.storageKey, options.defaultPos, options.bounds)
  }

  function saveCurrentPosition() {
    savePosition(options.storageKey, position.value)
    options.onDragEnd?.(position.value)
  }

  // Mouse events
  function onMouseDown(e: MouseEvent) {
    isDragging.value = true
    dragStartX.value = e.clientX - position.value.x
    dragStartY.value = e.clientY - position.value.y
    options.onDragStart?.()
  }

  function onMouseMove(e: MouseEvent) {
    if (!isDragging.value) return
    position.value = {
      x: Math.max(0, Math.min(options.bounds.maxX, e.clientX - dragStartX.value)),
      y: Math.max(0, Math.min(options.bounds.maxY, e.clientY - dragStartY.value)),
    }
  }

  function onMouseUp() {
    if (!isDragging.value) return
    isDragging.value = false
    saveCurrentPosition()
  }

  // Touch events
  function onTouchStart(e: TouchEvent) {
    isDragging.value = true
    const t = e.touches[0]
    dragStartX.value = t.clientX - position.value.x
    dragStartY.value = t.clientY - position.value.y
    options.onDragStart?.()
  }

  function onTouchMove(e: TouchEvent) {
    if (!isDragging.value) return
    e.preventDefault()
    position.value = {
      x: Math.max(0, Math.min(options.bounds.maxX, e.touches[0].clientX - dragStartX.value)),
      y: Math.max(0, Math.min(options.bounds.maxY, e.touches[0].clientY - dragStartY.value)),
    }
  }

  function onTouchEnd() {
    if (!isDragging.value) return
    isDragging.value = false
    saveCurrentPosition()
  }

  // Window resize handler
  function onWindowResize() {
    position.value = {
      x: Math.max(0, Math.min(options.bounds.maxX, position.value.x)),
      y: Math.max(0, Math.min(options.bounds.maxY, position.value.y)),
    }
  }

  onMounted(() => {
    loadSavedPosition()
    window.addEventListener('resize', onWindowResize)
  })

  onUnmounted(() => {
    window.removeEventListener('resize', onWindowResize)
  })

  return {
    isDragging,
    position,
    handlers: {
      onMouseDown,
      onMouseMove,
      onMouseUp,
      onTouchStart,
      onTouchMove,
      onTouchEnd,
    },
  }
}
