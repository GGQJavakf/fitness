module.exports = {
  presets: [
    [
      'taro',
      {
        framework: 'react',
        ts: true,
        compiler: 'webpack5',
        'dynamic-import-node': false,
        targets: {
          ios: '9',
          android: '5'
        },
        // Standard-library calls are handled by the explicit project plugin
        // below because Taro 4.2.1 usage mode does not rewrite every API used
        // by the application (notably padStart, flatMap and fromEntries).
        useBuiltIns: false,
        ignoreBrowserslistConfig: true
      }
    ]
  ],
  plugins: [
    require.resolve('./scripts/babel-plugin-weapp-runtime-compatibility.cjs')
  ]
}
