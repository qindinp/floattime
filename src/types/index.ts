// 类型定义

export type TimeSource = 'taobao' | 'meituan' | 'local'
export type ThemeMode = 'auto' | 'light' | 'dark'
export type SyncStatus = 'idle' | 'syncing' | 'success' | 'error'

export interface Position {
  x: number
  y: number
}

export interface TimeEndpoint {
  url: string
  type: 'header' | 'json'
  field?: string
}

export interface ProxyFunction {
  (url: string): string
}
