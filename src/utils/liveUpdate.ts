import { LIVE_UPDATE_CONFIG } from './config'

// 检查是否在 Capacitor 环境中
const isCapacitor = () => {
  return typeof (window as any).Capacitor !== 'undefined'
}

// 检查是否在 Android 环境中
const isAndroid = () => {
  return isCapacitor() && (window as any).Capacitor.getPlatform() === 'android'
}

/**
 * Live Update 管理器
 * 用于与 Android 16 Live Updates API 交互
 */
export class LiveUpdateManager {
  private static instance: LiveUpdateManager
  private bridge: any

  private constructor() {
    // 尝试获取 Android 桥接对象
    if (typeof (window as any).LiveUpdateBridge !== 'undefined') {
      this.bridge = (window as any).LiveUpdateBridge
    }
  }

  static getInstance(): LiveUpdateManager {
    if (!LiveUpdateManager.instance) {
      LiveUpdateManager.instance = new LiveUpdateManager()
    }
    return LiveUpdateManager.instance
  }

  /**
   * 检查是否支持 Live Updates
   */
  isSupported(): boolean {
    if (!LIVE_UPDATE_CONFIG.enabled) return false
    if (!isAndroid()) return false
    
    try {
      return this.bridge?.isLiveUpdateSupported() ?? false
    } catch (e) {
      console.warn('[LiveUpdate] Check support failed:', e)
      return false
    }
  }

  /**
   * 显示时间同步中状态
   */
  showTimeSyncing(source: string): void {
    if (!this.isSupported()) return
    
    try {
      this.bridge?.showTimeSyncing(source)
      console.log('[LiveUpdate] Showing time syncing:', source)
    } catch (e) {
      console.warn('[LiveUpdate] Show syncing failed:', e)
    }
  }

  /**
   * 显示时间同步成功
   */
  showTimeSyncSuccess(source: string, offsetMs: number): void {
    if (!this.isSupported()) return
    
    try {
      this.bridge?.showTimeSyncSuccess(source, offsetMs)
      console.log('[LiveUpdate] Showing sync success:', source, offsetMs)
    } catch (e) {
      console.warn('[LiveUpdate] Show success failed:', e)
    }
  }

  /**
   * 显示时间同步失败
   */
  showTimeSyncFailed(source: string): void {
    if (!this.isSupported()) return
    
    try {
      this.bridge?.showTimeSyncFailed(source)
      console.log('[LiveUpdate] Showing sync failed:', source)
    } catch (e) {
      console.warn('[LiveUpdate] Show failed failed:', e)
    }
  }

  /**
   * 启动秒表 Live Update
   */
  startStopwatch(): void {
    if (!this.isSupported()) return
    
    try {
      this.bridge?.startStopwatch()
      console.log('[LiveUpdate] Starting stopwatch')
    } catch (e) {
      console.warn('[LiveUpdate] Start stopwatch failed:', e)
    }
  }

  /**
   * 暂停秒表
   */
  pauseStopwatch(): void {
    if (!this.isSupported()) return
    
    try {
      this.bridge?.pauseStopwatch()
      console.log('[LiveUpdate] Pausing stopwatch')
    } catch (e) {
      console.warn('[LiveUpdate] Pause stopwatch failed:', e)
    }
  }

  /**
   * 停止秒表
   */
  stopStopwatch(): void {
    if (!this.isSupported()) return
    
    try {
      this.bridge?.stopStopwatch()
      console.log('[LiveUpdate] Stopping stopwatch')
    } catch (e) {
      console.warn('[LiveUpdate] Stop stopwatch failed:', e)
    }
  }
}

// 导出单例
export const liveUpdateManager = LiveUpdateManager.getInstance()
