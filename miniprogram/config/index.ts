import { defineConfig, type UserConfigExport } from '@tarojs/cli'

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
