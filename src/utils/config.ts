import type { ProxyFunction } from '../types'

// CORS 代理列表
export const CORS_PROXIES: ProxyFunction[] = [
  (url: string) => `https://corsproxy.io/?${encodeURIComponent(url)}`,
  (url: string) => `https://api.allorigins.win/raw?url=${encodeURIComponent(url)}`,
  (url: string) => `https://api.codetabs.com/v1/proxy?quest=${encodeURIComponent(url)}`,
]

// 时间源 API 配置
export const TIME_ENDPOINTS: Record<string, { url: string; type: 'header' | 'json'; field?: string }[]> = {
  taobao: [
    { url: 'https://api.m.taobao.com/gw/mtop.common.getTimestamp', type: 'json', field: 'data.t' },
    { url: 'https://time.taobao.com/api/servertime', type: 'json' },
    { url: 'https://api.taobao.com/router/rest', type: 'header' },
  ],
  meituan: [
    { url: 'https://api.meituan.com/nationalTimestamp', type: 'json' },
    { url: 'https://api-ssl.meituan.com/nationalTimestamp', type: 'json' },
    { url: 'https://www.meituan.com/', type: 'header' },
  ],
}

// 存储键名
export const STORAGE_KEYS = {
  BALL_POSITION: 'floattime-ball',
  PANEL_POSITION: 'floattime-panel',
  THEME: 'floattime-theme',
} as const

// 版本号
export const APP_VERSION = '1.2.1'
