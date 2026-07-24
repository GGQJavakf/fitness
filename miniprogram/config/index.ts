import { defineConfig, type UserConfigExport } from '@tarojs/cli'

const apiBaseUrl = process.env.TARO_APP_API_BASE_URL?.trim() || 'http://127.0.0.1:8080'

const config: UserConfigExport<'webpack5'> = {
  projectName: 'ai-fitness-miniprogram',
  date: '2026-07-23',
  designWidth: 750,
  sourceRoot: 'src',
  outputRoot: 'dist',
  framework: 'react',
  compiler: 'webpack5',
  cache: {
    enable: false
  },
  defineConstants: {
    __FITNESS_API_BASE_URL__: JSON.stringify(apiBaseUrl)
  },
  mini: {
    postcss: {
      pxtransform: {
        enable: true,
        config: {}
      },
      cssModules: {
        enable: false,
        config: {
          namingPattern: 'module',
          generateScopedName: '[name]__[local]___[hash:base64:5]'
        }
      }
    }
  }
}

export default defineConfig<'webpack5'>(config)
