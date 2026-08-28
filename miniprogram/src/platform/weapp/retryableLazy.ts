export function createRetryableLazyValue<T>(factory: () => T): () => T {
  let state: { initialized: false } | { initialized: true; value: T } = {
    initialized: false,
  }

  return () => {
    if (state.initialized) return state.value
    const value = factory()
    state = { initialized: true, value }
    return value
  }
}
