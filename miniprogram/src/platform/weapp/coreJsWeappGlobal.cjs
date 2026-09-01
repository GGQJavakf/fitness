'use strict'

// Webpack's WeChat target exposes `wx` as its runtime global. That is correct
// for chunk registration, but core-js-pure needs the JavaScript standard-
// library constructors rather than the WeChat API namespace. Supplying this
// adapter keeps its feature detection away from the Function-constructor
// browser fallback. Pure runtime helpers never patch these constructors.
const standardLibraryGlobal = {
  Array,
  Boolean,
  Date,
  Error,
  JSON,
  Math,
  Number,
  Object,
  Promise,
  RangeError,
  RegExp,
  String,
  TypeError,
}

if (typeof Map !== 'undefined') standardLibraryGlobal.Map = Map
if (typeof Set !== 'undefined') standardLibraryGlobal.Set = Set
if (typeof Symbol !== 'undefined') standardLibraryGlobal.Symbol = Symbol
if (typeof WeakMap !== 'undefined') standardLibraryGlobal.WeakMap = WeakMap
if (typeof WeakSet !== 'undefined') standardLibraryGlobal.WeakSet = WeakSet

module.exports = standardLibraryGlobal
