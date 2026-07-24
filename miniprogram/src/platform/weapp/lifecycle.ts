import { useDidHide, useDidShow } from '@tarojs/taro'

export function useWeappDidShow(effect: () => void): void {
  useDidShow(effect)
}

export function useWeappDidHide(effect: () => void): void {
  useDidHide(effect)
}
