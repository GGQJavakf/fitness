import { describe, expect, it } from 'vitest'

import { DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY } from '../../scripts/release-environment.mjs'

import {
  buildDeviceApiBaseUrl,
  miniprogramBuildEnvironment,
  missingRequiredEnvironment,
  normalizeDeviceApiBaseUrl,
  resolveNpmInvocation,
  selectDeviceHost
} from '../scripts/device-preflight.mjs'

describe('device preflight', () => {
  it('accepts an explicitly selected private IPv4 address', () => {
    expect(selectDeviceHost('192.168.10.22', [
      { address: '192.168.10.22', internal: false, family: 'IPv4' }
    ])).toBe('192.168.10.22')
  })

  it('rejects loopback and non-private addresses', () => {
    expect(() => selectDeviceHost('127.0.0.1', [])).toThrow(/private LAN IPv4/)
    expect(() => selectDeviceHost('8.8.8.8', [])).toThrow(/private LAN IPv4/)
  })

  it('requires an explicit host when more than one LAN address is available', () => {
    expect(() => selectDeviceHost(undefined, [
      { address: '10.0.210.95', internal: false, family: 'IPv4' },
      { address: '10.0.210.93', internal: false, family: 'IPv4' }
    ])).toThrow(/--host/)
  })

  it('rejects a private host that is not assigned to this computer', () => {
    expect(() => selectDeviceHost('192.168.10.99', [
      { address: '192.168.10.22', internal: false, family: 'IPv4' }
    ])).toThrow(/not assigned/)
  })

  it('selects the only available private LAN address', () => {
    expect(selectDeviceHost(undefined, [
      { address: '127.0.0.1', internal: true, family: 'IPv4' },
      { address: '172.16.2.8', internal: false, family: 'IPv4' }
    ])).toBe('172.16.2.8')
  })

  it('reports only missing environment key names', () => {
    expect(missingRequiredEnvironment({
      WECHAT_APP_ID: 'configured',
      WECHAT_APP_SECRET: 'configured',
      FITNESS_DB_URL: '',
      FITNESS_DB_USERNAME: undefined,
      FITNESS_DB_PASSWORD: 'configured'
    })).toEqual(['FITNESS_DB_URL', 'FITNESS_DB_USERNAME'])
  })

  it('builds a HTTPS device API URL without a trailing slash', () => {
    expect(buildDeviceApiBaseUrl('10.0.210.95', 8443)).toBe('https://10.0.210.95:8443')
  })

  it('normalizes an explicit HTTPS origin and rejects insecure or credential-bearing URLs', () => {
    expect(normalizeDeviceApiBaseUrl('https://fitness.example.test/')).toBe('https://fitness.example.test')
    expect(() => normalizeDeviceApiBaseUrl('http://10.0.210.95:8080')).toThrow(/must use HTTPS/)
    expect(() => normalizeDeviceApiBaseUrl('https://user:secret@fitness.example.test')).toThrow(/must not contain credentials/)
    expect(() => normalizeDeviceApiBaseUrl('https://fitness.example.test/base')).toThrow(/without a path/)
  })

  it('does not pass backend credentials into the miniprogram build', () => {
    expect(miniprogramBuildEnvironment({
      PATH: 'tool-path',
      WECHAT_APP_ID: 'app-id',
      WECHAT_APP_SECRET: 'secret',
      FITNESS_DB_URL: 'database-url',
      FITNESS_DB_USERNAME: 'database-user',
      FITNESS_DB_PASSWORD: 'database-password'
    }, 'https://10.0.210.95:8443')).toEqual({
      PATH: 'tool-path',
      TARO_APP_API_BASE_URL: 'https://10.0.210.95:8443',
      [DEVICE_BUILD_API_BASE_URL_ENVIRONMENT_KEY]: 'https://10.0.210.95:8443'
    })
  })

  it('uses the active npm JavaScript entry instead of spawning npm.cmd on Windows', () => {
    expect(resolveNpmInvocation(
      { npm_execpath: 'npm-cli-entry.js' },
      'node-runtime',
      'win32'
    )).toEqual({
      command: 'node-runtime',
      arguments: ['npm-cli-entry.js', 'run', 'build:weapp']
    })
  })
})
