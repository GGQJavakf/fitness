import Taro from '@tarojs/taro'

export async function recoverWeappHome(): Promise<void> {
  await Taro.reLaunch({
    url: '/presentation/pages/home/index',
  })
}
