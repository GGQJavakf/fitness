import { Text, View } from '@tarojs/components'

import './index.scss'

export default function HomePage() {
  return (
    <View className='home-page'>
      <Text className='home-page__title'>AI 健身助手</Text>
      <Text className='home-page__status'>工程基线已就绪</Text>
    </View>
  )
}
