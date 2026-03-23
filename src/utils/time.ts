import type { Position } from '../types'

// 带代理的 fetch
export async function fetchWithProxy(
  url: string,
  signal: AbortSignal,
  proxyFns: ((url: string) => string)[]
): Promise<Response> {
  for (let i = 0; i < proxyFns.length; i++) {
    const proxyFn = proxyFns[i]
    const proxyUrl = proxyFn(url)
    try {
      const resp = await fetch(proxyUrl, { method: 'GET', cache: 'no-cache', signal })
      if (resp.ok || resp.status === 200) return resp
      console.warn(`[proxy] #${i} returned ${resp.status} for ${url}`)
    } catch (e) {
      if ((e as Error).name === 'AbortError') throw e
      console.warn(`[proxy] #${i} failed for ${url}:`, e)
    }
  }
  throw new Error('All CORS proxies failed')
}

// 解析服务器时间
export function parseServerTime(value: unknown): number | null {
  let ts: number | null = null
  if (typeof value === 'number') ts = value
  else if (typeof value === 'string') ts = parseInt(value, 10)
  else return null
  if (isNaN(ts) || ts === 0) return null
  if (ts > 1000000000 && ts < 4102444800) return ts * 1000
  if (ts > 1000000000000 && ts < 4102444800000) return ts
  return null
}

// 格式化毫秒为秒表显示
export function formatMs(ms: number): string {
  const s = Math.floor(ms / 1000)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}.${String(Math.floor((ms % 1000) / 10)).padStart(2, '0')}`
}

// 格式化时间为 HH:MM:SS
export function formatTime(d: Date): string {
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
}

// 格式化为 YYYY/MM/DD
export function formatDate(d: Date): string {
  return `${d.getFullYear()}/${String(d.getMonth() + 1).padStart(2, '0')}/${String(d.getDate()).padStart(2, '0')}`
}

// 从 JSON 中提取嵌套字段
export function getNestedValue(obj: unknown, path: string): unknown {
  const keys = path.split('.')
  let v: unknown = obj
  for (const k of keys) {
    if (v === null || typeof v !== 'object') return undefined
    v = (v as Record<string, unknown>)[k]
    if (v === undefined) break
  }
  return v
}
