import { useDidShow } from '@tarojs/taro'

export function useWeappDidShow(effect: () => void): void {
  useDidShow(effect)
}
