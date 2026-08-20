export type WeappRuntimeConfigurationIssue = 'DEVICE_LOOPBACK_API'

interface WeappRuntimeConfiguration {
  apiBaseUrl: string
  cloudBaseServiceName: string
  platform?: string
}

interface WechatDeviceRuntime {
  getDeviceInfo?(): { platform?: string }
  getSystemInfoSync?(): { platform?: string }
}

declare const wx: WechatDeviceRuntime | undefined

const physicalDevicePlatforms = new Set(['android', 'ios', 'harmonyos', 'ohos'])
const loopbackApiOrigin = /^https?:\/\/(?:localhost|127(?:\.\d{1,3}){3}|0\.0\.0\.0|\[?::1\]?)(?::\d+)?(?:\/|$)/i

export function inspectWeappRuntimeConfiguration(
  configuration: WeappRuntimeConfiguration,
): WeappRuntimeConfigurationIssue | undefined {
  if (configuration.cloudBaseServiceName.trim()) return undefined
  const platform = configuration.platform?.trim().toLowerCase()
  if (!platform || !physicalDevicePlatforms.has(platform)) return undefined
  return loopbackApiOrigin.test(configuration.apiBaseUrl.trim())
    ? 'DEVICE_LOOPBACK_API'
    : undefined
}

export function currentWeappRuntimeConfigurationIssue(
  configuration: Omit<WeappRuntimeConfiguration, 'platform'>,
): WeappRuntimeConfigurationIssue | undefined {
  const runtime = typeof wx === 'undefined' ? undefined : wx
  const platform = readPlatform(runtime)
  return inspectWeappRuntimeConfiguration({ ...configuration, platform })
}

function readPlatform(runtime: WechatDeviceRuntime | undefined): string | undefined {
  for (const read of [
    () => runtime?.getDeviceInfo?.().platform,
    () => runtime?.getSystemInfoSync?.().platform,
  ]) {
    try {
      const platform = read()?.trim()
      if (platform) return platform
    } catch {
      // Try the compatibility API before leaving the runtime classification unknown.
    }
  }
  return undefined
}
