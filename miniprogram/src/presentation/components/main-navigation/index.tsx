import { Button, View } from '@tarojs/components'

import type { PageDestination } from '../../../application/navigation'

import './index.scss'

type MainDestination = Extract<PageDestination, 'PLAN' | 'HISTORY' | 'MY'>

interface MainNavigationProps {
  current: MainDestination
  onNavigate(destination: MainDestination): void
}

const items: ReadonlyArray<{ destination: MainDestination; label: string }> = [
  { destination: 'PLAN', label: '计划' },
  { destination: 'HISTORY', label: '历史' },
  { destination: 'MY', label: '我的' },
]

export default function MainNavigation({ current, onNavigate }: MainNavigationProps) {
  return (
    <View className='main-navigation' role='navigation' aria-label='主导航'>
      {items.map((item) => (
        <Button
          key={item.destination}
          className={`main-navigation__item ${current === item.destination ? 'main-navigation__item--active' : ''}`}
          disabled={current === item.destination}
          onClick={() => onNavigate(item.destination)}
        >{item.label}</Button>
      ))}
    </View>
  )
}
