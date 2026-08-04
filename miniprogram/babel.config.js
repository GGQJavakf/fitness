module.exports = {
  presets: [
    [
      'taro',
      {
        framework: 'react',
        ts: true,
        compiler: 'webpack5',
        targets: {
          ios: '9',
          android: '5'
        },
        ignoreBrowserslistConfig: true
      }
    ]
  ]
}
