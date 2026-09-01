'use strict'

const INSTANCE_HELPERS = Object.freeze({
  at: '@babel/runtime-corejs3/core-js/instance/at',
  flatMap: '@babel/runtime-corejs3/core-js-stable/instance/flat-map',
  includes: '@babel/runtime-corejs3/core-js-stable/instance/includes',
  padStart: '@babel/runtime-corejs3/core-js-stable/instance/pad-start',
  startsWith: '@babel/runtime-corejs3/core-js-stable/instance/starts-with',
})

const UNCURRIED_INSTANCE_HELPERS = Object.freeze({
  finally: 'core-js-pure/stable/promise/finally',
})

const STATIC_HELPERS = Object.freeze({
  'Array.from': '@babel/runtime-corejs3/core-js-stable/array/from',
  'Number.isSafeInteger': '@babel/runtime-corejs3/core-js-stable/number/is-safe-integer',
  'Object.entries': '@babel/runtime-corejs3/core-js-stable/object/entries',
  'Object.fromEntries': '@babel/runtime-corejs3/core-js-stable/object/from-entries',
  'Object.values': '@babel/runtime-corejs3/core-js-stable/object/values',
})

module.exports = function weappRuntimeCompatibilityPlugin({ types: t }) {
  function declaredHelper(helpers, key) {
    return Object.prototype.hasOwnProperty.call(helpers, key)
      ? helpers[key]
      : undefined
  }

  function isProjectSource(state) {
    const filename = String(state.file.opts.filename ?? '').replace(/\\/g, '/')
    return filename.includes('/src/') && !filename.includes('/node_modules/')
  }

  function helperIdentifier(path, state, source, hint) {
    let helpers = state.file.get('fitnessWeappRuntimeCompatibilityHelpers')
    if (!helpers) {
      helpers = new Map()
      state.file.set('fitnessWeappRuntimeCompatibilityHelpers', helpers)
    }
    const existing = helpers.get(source)
    if (existing) return t.cloneNode(existing)

    const program = path.findParent((candidate) => candidate.isProgram())
    if (!program) throw path.buildCodeFrameError('Compatibility call is outside a Program')
    const identifier = program.scope.generateUidIdentifier(`fitness_${hint}`)
    program.unshiftContainer('body', t.importDeclaration(
      [t.importDefaultSpecifier(t.cloneNode(identifier))],
      t.stringLiteral(source),
    ))
    helpers.set(source, identifier)
    return t.cloneNode(identifier)
  }

  function strictNullish(identifier) {
    return t.logicalExpression(
      '||',
      t.binaryExpression('===', t.cloneNode(identifier), t.nullLiteral()),
      t.binaryExpression(
        '===',
        t.cloneNode(identifier),
        t.unaryExpression('void', t.numericLiteral(0)),
      ),
    )
  }

  function rewriteStaticCall(path, state, callee) {
    if (!callee.isMemberExpression() || callee.node.computed) return false
    const object = callee.get('object')
    const property = callee.get('property')
    if (!object.isIdentifier() || !property.isIdentifier()) return false
    const key = `${object.node.name}.${property.node.name}`
    const source = declaredHelper(STATIC_HELPERS, key)
    if (!source) return false
    path.replaceWith(t.callExpression(
      helperIdentifier(path, state, source, property.node.name),
      path.node.arguments,
    ))
    return true
  }

  function rewriteInstanceCall(path, state, callee) {
    if (
      (!callee.isMemberExpression() && !callee.isOptionalMemberExpression())
      || callee.node.computed
    ) return false
    const property = callee.get('property')
    if (!property.isIdentifier()) return false
    const source = declaredHelper(INSTANCE_HELPERS, property.node.name)
      ?? declaredHelper(UNCURRIED_INSTANCE_HELPERS, property.node.name)
    if (!source) return false
    const isUncurried = declaredHelper(
      UNCURRIED_INSTANCE_HELPERS,
      property.node.name,
    ) !== undefined

    const receiver = path.scope.generateUidIdentifier('fitnessContext')
    path.scope.push({ id: t.cloneNode(receiver) })
    const assignReceiver = t.assignmentExpression(
      '=',
      t.cloneNode(receiver),
      callee.node.object,
    )
    const helperCall = t.callExpression(
      helperIdentifier(path, state, source, property.node.name),
      [t.cloneNode(receiver)],
    )
    let invocation
    if (isUncurried) {
      invocation = t.callExpression(
        helperIdentifier(path, state, source, property.node.name),
        [t.cloneNode(receiver), ...path.node.arguments],
      )
    } else if (path.node.optional) {
      const method = path.scope.generateUidIdentifier('fitnessMethod')
      path.scope.push({ id: t.cloneNode(method) })
      const assignMethod = t.assignmentExpression('=', t.cloneNode(method), helperCall)
      invocation = t.sequenceExpression([
        assignMethod,
        t.conditionalExpression(
          strictNullish(method),
          t.unaryExpression('void', t.numericLiteral(0)),
          t.callExpression(
            t.memberExpression(t.cloneNode(method), t.identifier('call')),
            [t.cloneNode(receiver), ...path.node.arguments],
          ),
        ),
      ])
    } else {
      invocation = t.callExpression(
        t.memberExpression(helperCall, t.identifier('call')),
        [t.cloneNode(receiver), ...path.node.arguments],
      )
    }

    const evaluatedInvocation = t.sequenceExpression([assignReceiver, invocation])
    path.replaceWith(callee.node.optional
      ? t.sequenceExpression([
          assignReceiver,
          t.conditionalExpression(
            strictNullish(receiver),
            t.unaryExpression('void', t.numericLiteral(0)),
            invocation,
          ),
        ])
      : evaluatedInvocation)
    return true
  }

  return {
    name: 'fitness-weapp-runtime-compatibility',
    visitor: {
      'CallExpression|OptionalCallExpression'(path, state) {
        if (!isProjectSource(state)) return
        const callee = path.get('callee')
        if (rewriteStaticCall(path, state, callee)) return
        rewriteInstanceCall(path, state, callee)
      },
    },
  }
}

module.exports.INSTANCE_HELPERS = INSTANCE_HELPERS
module.exports.STATIC_HELPERS = STATIC_HELPERS
module.exports.UNCURRIED_INSTANCE_HELPERS = UNCURRIED_INSTANCE_HELPERS
