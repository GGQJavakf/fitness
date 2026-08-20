import { describe, expect, it } from 'vitest'

import { inspectWeappRuntimeConfiguration } from '../src/platform/weapp/runtimeConfiguration'

describe('WeChat runtime configuration', () => {
  it('blocks a direct loopback API origin on a physical device', () => {
    expect(inspectWeappRuntimeConfiguration({
      apiBaseUrl: 'http://127.0.0.1:8080',
      cloudBaseServiceName: '',
      platform: 'android',
    })).toBe('DEVICE_LOOPBACK_API')
    expect(inspectWeappRuntimeConfiguration({
      apiBaseUrl: 'http://localhost:8080',
      cloudBaseServiceName: '',
      platform: 'harmonyos',
    })).toBe('DEVICE_LOOPBACK_API')
  })

  it('allows simulator loopback, HTTPS device origins, and CloudBase container transport', () => {
    expect(inspectWeappRuntimeConfiguration({
      apiBaseUrl: 'http://127.0.0.1:8080',
      cloudBaseServiceName: '',
      platform: 'devtools',
    })).toBeUndefined()
    expect(inspectWeappRuntimeConfiguration({
      apiBaseUrl: 'https://fitness.example.test',
      cloudBaseServiceName: '',
      platform: 'ios',
    })).toBeUndefined()
    expect(inspectWeappRuntimeConfiguration({
      apiBaseUrl: 'http://127.0.0.1:8080',
      cloudBaseServiceName: 'fitness-api',
      platform: 'android',
    })).toBeUndefined()
  })
})
