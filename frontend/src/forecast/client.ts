import { run, type ForecastReply, type ForecastRequest } from './run.ts'

/**
 * Forecasts in a short-lived Web Worker, so the page stays responsive, and falls back to running inline where
 * workers are not available. The transactions stay in this tab's memory and are dropped when the worker ends.
 */
export function forecastOnDevice(request: ForecastRequest): Promise<ForecastReply> {
  if (typeof Worker === 'undefined') return Promise.resolve(run(request))
  return new Promise((resolve) => {
    const worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' })
    const finish = (reply: ForecastReply) => {
      worker.terminate()
      resolve(reply)
    }
    worker.onmessage = (event: MessageEvent<ForecastReply>) => finish(event.data)
    worker.onerror = () => finish(run(request))
    worker.postMessage(request)
  })
}
