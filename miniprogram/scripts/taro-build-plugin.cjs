const path = require('node:path')

const BUILD_HOOKS = [
  'modifyAppConfig',
  'modifyWebpackChain',
  'modifyViteConfig',
  'modifyBuildAssets',
  'modifyMiniConfigs',
  'modifyComponentConfig',
  'modifyRunnerOpts',
  'onCompilerMake',
  'onParseCreateElement',
  'onBuildStart',
  'onBuildFinish',
  'onBuildComplete',
]

module.exports = (ctx) => {
  for (const hook of BUILD_HOOKS) {
    ctx.registerMethod(hook)
  }

  ctx.registerMethod('writeFileToDist', ({ filePath, content }) => {
    if (path.isAbsolute(filePath)) {
      throw new Error('writeFileToDist only accepts a path relative to the output directory')
    }
    const outputPath = path.resolve(ctx.paths.outputPath)
    const target = path.resolve(outputPath, filePath)
    const relativeTarget = path.relative(outputPath, target)
    if (
      relativeTarget === '..'
      || relativeTarget.startsWith(`..${path.sep}`)
      || path.isAbsolute(relativeTarget)
    ) {
      throw new Error('writeFileToDist cannot write outside the output directory')
    }
    ctx.helper.fs.ensureDirSync(path.dirname(target))
    ctx.helper.fs.writeFileSync(target, content)
  })

  ctx.registerMethod('generateProjectConfig', ({ srcConfigName, distConfigName }) => {
    const source = [
      path.join(ctx.paths.appPath, srcConfigName),
      path.join(ctx.paths.sourcePath, srcConfigName),
    ].find(candidate => ctx.helper.fs.existsSync(candidate))
    if (!source) return

    const projectConfig = ctx.helper.fs.readJSONSync(source)
    projectConfig.appid = process.env.TARO_APP_ID || projectConfig.appid
    if (projectConfig.compileType !== 'plugin') {
      projectConfig.miniprogramRoot = './'
    }
    ctx.writeFileToDist({
      filePath: distConfigName,
      content: JSON.stringify(projectConfig, null, 2),
    })
  })

  ctx.registerCommand({
    name: 'build',
    async fn({ options, config }) {
      const { platform, isWatch } = options
      if (platform !== 'weapp') {
        throw new Error('This project-local build runner only supports the weapp platform')
      }
      if (!ctx.paths.configPath || !ctx.helper.fs.existsSync(ctx.paths.configPath)) {
        throw new Error(`Taro project config is missing: ${ctx.paths.configPath || 'config/index'}`)
      }

      const { validateConfig } = require('@tarojs/plugin-doctor')
      const validation = await validateConfig(ctx.initialConfig, ctx.helper)
      if (!validation.isValid) {
        const details = validation.messages
          .filter(message => message.kind === 1)
          .map(message => message.content)
          .join('; ')
        throw new Error(`Taro project config is invalid${details ? `: ${details}` : ''}`)
      }

      ctx.helper.fs.ensureDirSync(ctx.paths.outputPath)
      await ctx.applyPlugins('onBuildStart')
      await ctx.applyPlugins({
        name: platform,
        opts: {
          config: {
            ...config,
            isWatch,
            mode: isWatch ? 'development' : 'production',
            blended: false,
            isBuildNativeComp: false,
            withoutBuild: false,
            newBlended: false,
            noInjectGlobalStyle: false,
            async modifyAppConfig(appConfig) {
              await ctx.applyPlugins({ name: 'modifyAppConfig', opts: { appConfig } })
            },
            async modifyWebpackChain(chain, webpack, data) {
              await ctx.applyPlugins({
                name: 'modifyWebpackChain',
                initialVal: chain,
                opts: { chain, webpack, data },
              })
            },
            async modifyViteConfig(viteConfig, data, viteCompilerContext) {
              await ctx.applyPlugins({
                name: 'modifyViteConfig',
                initialVal: viteConfig,
                opts: { viteConfig, data, viteCompilerContext },
              })
            },
            async modifyBuildAssets(assets, miniPlugin) {
              await ctx.applyPlugins({
                name: 'modifyBuildAssets',
                initialVal: assets,
                opts: { assets, miniPlugin },
              })
            },
            async modifyMiniConfigs(configMap) {
              await ctx.applyPlugins({
                name: 'modifyMiniConfigs',
                initialVal: configMap,
                opts: { configMap },
              })
            },
            async modifyComponentConfig(componentConfig, componentBuildConfig) {
              await ctx.applyPlugins({
                name: 'modifyComponentConfig',
                opts: { componentConfig, config: componentBuildConfig },
              })
            },
            async onCompilerMake(compilation, compiler, plugin) {
              await ctx.applyPlugins({
                name: 'onCompilerMake',
                opts: { compilation, compiler, plugin },
              })
            },
            async onParseCreateElement(nodeName, componentConfig) {
              await ctx.applyPlugins({
                name: 'onParseCreateElement',
                opts: { nodeName, componentConfig },
              })
            },
            async onBuildFinish({ error, stats, isWatch: finishingWatch }) {
              await ctx.applyPlugins({
                name: 'onBuildFinish',
                opts: { error, stats, isWatch: finishingWatch },
              })
            },
          },
        },
      })
      await ctx.applyPlugins('onBuildComplete')
    },
  })
}
