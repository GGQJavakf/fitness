import Taro from '@tarojs/taro'
import { useEffect, useRef } from 'react'

/**
 * Runs a page-start callback only after the mini-program view layer reports its
 * initial render complete. The extra nextTick keeps cached chunks and redirects
 * from racing the first native paint on Android.
 */
export function useWeappFirstPaint(callback: () => void): void {
  const callbackRef = useRef(callback)
  const startedRef = useRef(false)
  const mountedRef = useRef(true)
  callbackRef.current = callback

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
    }
  }, [])

  Taro.useReady(() => {
    if (startedRef.current) return
    startedRef.current = true
    Taro.nextTick(() => {
      if (mountedRef.current) callbackRef.current()
    })
  })
}
