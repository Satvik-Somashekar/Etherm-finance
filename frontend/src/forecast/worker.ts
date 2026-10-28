// Runs the forecast off the page's main thread. It receives the transactions, posts back the forecast, and keeps
// nothing: no storage, no network.

import { run, type ForecastRequest } from './run.ts'

const scope = self as unknown as Worker
scope.onmessage = (event: MessageEvent<ForecastRequest>) => scope.postMessage(run(event.data))
