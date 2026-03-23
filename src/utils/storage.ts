import type { Position } from '../types'

export function loadPosition(key: string, defaultPos: Position, bounds: { maxX: number; maxY: number }): Position {
  try {
    const raw = localStorage.getItem(key)
    if (raw) {
      const p = JSON.parse(raw)
      if (typeof p.x === 'number' && typeof p.y === 'number') {
        return {
          x: Math.max(0, Math.min(bounds.maxX, p.x)),
          y: Math.max(0, Math.min(bounds.maxY, p.y)),
        }
      }
    }
  } catch {
    // ignore
  }
  return defaultPos
}

export function savePosition(key: string, pos: Position): void {
  localStorage.setItem(key, JSON.stringify(pos))
}

export function loadString(key: string, defaultValue: string): string {
  try {
    return localStorage.getItem(key) ?? defaultValue
  } catch {
    return defaultValue
  }
}

export function saveString(key: string, value: string): void {
  localStorage.setItem(key, value)
}
