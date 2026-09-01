interface SingleFlightState { current: boolean }

export async function runSingleFlight(
  state: SingleFlightState,
  setBusy: (busy: boolean) => void,
  operation: () => Promise<void>,
): Promise<void> {
  if (state.current) return

  state.current = true
  setBusy(true)
  try {
    await operation()
  } finally {
    state.current = false
    setBusy(false)
  }
}
