'use strict'

const PLUGIN_NAME = 'FitnessWeappNativeAsyncChunkWebpackPlugin'
const CHUNK_LOADING_TYPE = 'jsonp'
const ASYNC_CHUNK_ENVELOPE_MARKER = '__fitnessWebpackChunkEnvelope'
const ASYNC_CHUNK_ENVELOPE_VERSION = 1
const NATIVE_ASYNC_CHUNK_PATH =
  /^subpackages\/(?:startup|planning|workout|progress|account|exercise-guide)\/async\/[a-z0-9][a-z0-9._/-]*\.js$/

class WeappNativeAsyncChunkWebpackPlugin {
  apply(compiler) {
    compiler.hooks.thisCompilation.tap(PLUGIN_NAME, (compilation) => {
      const {
        Compilation,
        RuntimeGlobals,
        RuntimeModule,
        Template,
        sources: { RawSource },
      } = compiler.webpack
      const installedForChunk = new WeakSet()
      const installedGlobalForChunk = new WeakSet()

      compilation.hooks.processAssets.tap(
        {
          name: PLUGIN_NAME,
          stage: Compilation.PROCESS_ASSETS_STAGE_OPTIMIZE_INLINE,
        },
        () => {
          const chunkLoadingGlobal = compilation.outputOptions.chunkLoadingGlobal
          for (const chunk of compilation.chunks) {
            if (typeof chunk.name !== 'string' || !chunk.name) continue
            for (const file of chunk.files) {
              if (normalizeNativeAsyncChunkUrl(file) === undefined) continue
              const asset = compilation.getAsset(file)
              if (!asset) continue
              const encoded = encodeNativeAsyncChunkEnvelope(
                asset.source.source().toString(),
                chunkLoadingGlobal,
                file,
              )
              compilation.updateAsset(file, new RawSource(encoded), asset.info)
            }
          }
        },
      )

      compilation.hooks.runtimeRequirementInTree
        .for(RuntimeGlobals.global)
        .tap(PLUGIN_NAME, (chunk) => {
          if (installedGlobalForChunk.has(chunk)) return true
          installedGlobalForChunk.add(chunk)

          class WeappGlobalRuntimeModule extends RuntimeModule {
            constructor() {
              super('wechat global object', RuntimeModule.STAGE_ATTACH)
            }

            generate() {
              return `${RuntimeGlobals.global} = wx;`
            }
          }

          compilation.addRuntimeModule(chunk, new WeappGlobalRuntimeModule())
          // Avoid Webpack's fallback to `new Function('return this')()`.
          return true
        })

      compilation.hooks.runtimeRequirementInTree
        .for(RuntimeGlobals.loadScript)
        .tap(PLUGIN_NAME, (chunk, runtimeRequirements) => {
          runtimeRequirements.add(RuntimeGlobals.global)
          if (installedForChunk.has(chunk)) return true
          installedForChunk.add(chunk)

          class WeappLoadScriptRuntimeModule extends RuntimeModule {
            constructor() {
              super('wechat require.async load script', RuntimeModule.STAGE_ATTACH)
            }

            generate() {
              const loadScript = RuntimeGlobals.loadScript
              const nativeAsyncChunkFiles = collectNativeAsyncChunkFiles(compilation)
              const chunkLoadingGlobal = compilation.outputOptions.chunkLoadingGlobal
              const literalDispatchCases = nativeAsyncChunkFiles.map((path) => (
                `case ${JSON.stringify(path)}: return require.async(${JSON.stringify(`./${path}`)});`
              ))
              return Template.asString([
                '// Native WeChat replacement for Webpack script loading.',
                'var inProgressWeappScripts = Object.create(null);',
                '// WeChat precompilation only recognizes a single static string literal',
                '// in each require.async call. Keep the dispatch exhaustive and literal.',
                'var requireWeappAsyncChunk = function(normalizedChunkPath) {',
                Template.indent([
                  'switch(normalizedChunkPath) {',
                  Template.indent([
                    ...literalDispatchCases,
                    'default: return undefined;',
                  ]),
                  '}',
                ]),
                '};',
                'var installWeappAsyncChunk = function(envelope) {',
                Template.indent([
                  `if(!envelope || envelope[${JSON.stringify(ASYNC_CHUNK_ENVELOPE_MARKER)}] !== ${ASYNC_CHUNK_ENVELOPE_VERSION} || !Array.isArray(envelope.registration)) {`,
                  Template.indent("throw new Error('Invalid WeChat async chunk registration envelope');"),
                  '}',
                  'var asyncModuleFactories = envelope.registration[1];',
                  'if(!asyncModuleFactories || typeof asyncModuleFactories !== \'object\') {',
                  Template.indent("throw new Error('Invalid WeChat async chunk module table');"),
                  '}',
                  'for(var asyncModuleId in asyncModuleFactories) {',
                  Template.indent([
                    'if(!Object.prototype.hasOwnProperty.call(asyncModuleFactories, asyncModuleId)) continue;',
                    'var asyncModuleFactory = asyncModuleFactories[asyncModuleId];',
                    'if(typeof asyncModuleFactory !== \'function\') continue;',
                    'asyncModuleFactories[asyncModuleId] = (function(moduleId, moduleFactory) {',
                    Template.indent([
                      'return function(module, exports, webpackRequire) {',
                      Template.indent([
                        'try {',
                        Template.indent('return moduleFactory.call(this, module, exports, webpackRequire);'),
                        '} catch(cause) {',
                        Template.indent([
                          "if(cause && cause.type === 'fitness-module-evaluation') throw cause;",
                          "var evaluationError = new Error('WeChat async module evaluation failed');",
                          "evaluationError.name = 'FitnessAsyncModuleEvaluationError';",
                          "evaluationError.type = 'fitness-module-evaluation';",
                          'evaluationError.moduleId = String(moduleId);',
                          'evaluationError.cause = cause;',
                          'throw evaluationError;',
                        ]),
                        '}',
                      ]),
                      '};',
                    ]),
                    '})(asyncModuleId, asyncModuleFactory);',
                  ]),
                  '}',
                  `var chunkQueue = ${RuntimeGlobals.global}[${JSON.stringify(chunkLoadingGlobal)}] = ${RuntimeGlobals.global}[${JSON.stringify(chunkLoadingGlobal)}] || [];`,
                  'chunkQueue.push(envelope.registration);',
                ]),
                '};',
                `${loadScript} = function(url, done) {`,
                Template.indent([
                  'if(inProgressWeappScripts[url]) {',
                  Template.indent([
                    'inProgressWeappScripts[url].callbacks.push(done);',
                    'return;',
                  ]),
                  '}',
                  'var currentAttempt = inProgressWeappScripts[url] = { callbacks: [done] };',
                  'var normalizedChunkPath = String(url).replace(/\\\\/g, \'/\');',
                  "if(normalizedChunkPath.slice(0, 2) === './') normalizedChunkPath = normalizedChunkPath.slice(2);",
                  "if(normalizedChunkPath.charAt(0) === '/' && normalizedChunkPath.charAt(1) !== '/') normalizedChunkPath = normalizedChunkPath.slice(1);",
                  "var chunkPath = './' + normalizedChunkPath;",
                  'var finishWeappScript = function(type, cause) {',
                  Template.indent([
                    'if(inProgressWeappScripts[url] !== currentAttempt) return;',
                    'var callbacks = currentAttempt.callbacks;',
                    'delete inProgressWeappScripts[url];',
                    'var event = { type: type, target: { src: chunkPath } };',
                    'if(cause !== undefined) event.error = cause;',
                    'for(var index = 0; index < callbacks.length; index++) callbacks[index](event);',
                  ]),
                  '};',
                  `if(!${NATIVE_ASYNC_CHUNK_PATH.toString()}.test(normalizedChunkPath) || normalizedChunkPath.split('/').indexOf('..') >= 0) {`,
                  Template.indent([
                    "finishWeappScript('error', new Error('Unsafe WeChat async chunk path: ' + url));",
                    'return;',
                  ]),
                  '}',
                  'var pendingWeappChunk;',
                  'try {',
                  Template.indent([
                    'pendingWeappChunk = requireWeappAsyncChunk(normalizedChunkPath);',
                    "if(!pendingWeappChunk || typeof pendingWeappChunk.then !== 'function') {",
                    Template.indent([
                      "throw new Error('No static WeChat require.async mapping for ' + chunkPath);",
                    ]),
                    '}',
                    'pendingWeappChunk.then(function(envelope) {',
                    Template.indent([
                      'if(inProgressWeappScripts[url] !== currentAttempt) return;',
                      'try {',
                      Template.indent([
                        'installWeappAsyncChunk(envelope);',
                        "finishWeappScript('load');",
                      ]),
                      '} catch(cause) {',
                      Template.indent("finishWeappScript('fitness-envelope-invalid', cause);"),
                      '}',
                    ]),
                    '}, function(cause) {',
                    Template.indent("finishWeappScript('fitness-native-require', cause);"),
                    '});',
                  ]),
                  '} catch(cause) {',
                  Template.indent("finishWeappScript('fitness-native-require', cause);"),
                  '}',
                ]),
                '};',
              ])
            }
          }

          compilation.addRuntimeModule(chunk, new WeappLoadScriptRuntimeModule())
          // SyncBailHook: prevent Webpack's DOM LoadScriptRuntimeModule from being added.
          return true
        })
    })
  }
}

function encodeNativeAsyncChunkEnvelope(source, chunkLoadingGlobal, file = 'async chunk') {
  if (typeof source !== 'string') {
    throw new TypeError(`Cannot encode non-text WeChat async chunk: ${file}`)
  }
  const trailingWhitespace = source.slice(source.trimEnd().length)
  const trimmed = source.trimEnd()
  const globalKey = escapeRegExp(JSON.stringify(chunkLoadingGlobal))
  const wrapper = new RegExp(
    `^(?:["']use strict["'];\\s*)?\\(wx\\[${globalKey}\\]\\s*=\\s*wx\\[${globalKey}\\]\\s*\\|\\|\\s*\\[\\]\\)\\.push\\(`,
  )
  const match = wrapper.exec(trimmed)
  if (!match || !trimmed.endsWith(');')) {
    throw new Error(`WeChat async chunk is not a supported JSONP registration: ${file}`)
  }
  const registration = trimmed.slice(match[0].length, -2)
  if (!registration.startsWith('[')) {
    throw new Error(`WeChat async chunk registration payload is invalid: ${file}`)
  }
  return [
    '"use strict";module.exports={',
    `${JSON.stringify(ASYNC_CHUNK_ENVELOPE_MARKER)}:${ASYNC_CHUNK_ENVELOPE_VERSION},`,
    `registration:${registration}`,
    `};${trailingWhitespace}`,
  ].join('')
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function collectNativeAsyncChunkFiles(compilation) {
  const paths = new Set()
  for (const chunk of compilation.chunks) {
    if (typeof chunk.name !== 'string' || !chunk.name) continue
    const path = normalizeNativeAsyncChunkUrl(`${chunk.name}.js`)
    if (path !== undefined) paths.add(path)
  }
  return [...paths].sort()
}

function normalizeNativeAsyncChunkUrl(value) {
  if (typeof value !== 'string') return undefined
  let normalized = value.trim().replace(/\\/g, '/')
  if (!normalized || /^[a-z][a-z0-9+.-]*:/i.test(normalized)) return undefined
  if (normalized.startsWith('//')) return undefined
  if (normalized.startsWith('./')) normalized = normalized.slice(2)
  if (normalized.startsWith('/') && !normalized.startsWith('//')) {
    normalized = normalized.slice(1)
  }
  if (!NATIVE_ASYNC_CHUNK_PATH.test(normalized)) return undefined
  if (normalized.split('/').includes('..')) return undefined
  return normalized
}

function isSafeNativeAsyncChunkUrl(value) {
  return normalizeNativeAsyncChunkUrl(value) !== undefined
}

module.exports = WeappNativeAsyncChunkWebpackPlugin
module.exports.CHUNK_LOADING_TYPE = CHUNK_LOADING_TYPE
module.exports.ASYNC_CHUNK_ENVELOPE_MARKER = ASYNC_CHUNK_ENVELOPE_MARKER
module.exports.encodeNativeAsyncChunkEnvelope = encodeNativeAsyncChunkEnvelope
module.exports.normalizeNativeAsyncChunkUrl = normalizeNativeAsyncChunkUrl
module.exports.isSafeNativeAsyncChunkUrl = isSafeNativeAsyncChunkUrl
