// Rungs 1 and 2: one-step-ahead forecasts of a monthly series. Every model is a few lines of arithmetic over
// at most a few dozen numbers, fitted by grid search on its own one-step errors, so a series costs well under a
// millisecond and nothing is learned across customers.

export interface Model {
  name: string
  /** Rung 1 (baselines) or rung 2 (smoothing). */
  rung: 1 | 2
  /** Shortest history the model can forecast from. */
  minLength: number
  /** Fits on y and returns the forecast for the month after it. */
  next(y: number[]): number
}

const SEASON = 12

const mean = (y: number[]) => y.reduce((s, v) => s + v, 0) / y.length

/** Sum of squared one-step errors and the final forecast for simple exponential smoothing. */
function ses(y: number[], alpha: number) {
  let level = y[0]
  let sse = 0
  for (let t = 1; t < y.length; t++) {
    const e = y[t] - level
    sse += e * e
    level += alpha * e
  }
  return { sse, next: level }
}

/** Damped-trend Holt: the trend fades by phi each month, so it never runs away on short histories. */
function holt(y: number[], alpha: number, beta: number, phi: number) {
  let level = y[0]
  let trend = y[1] - y[0]
  let sse = 0
  for (let t = 1; t < y.length; t++) {
    const f = level + phi * trend
    const e = y[t] - f
    sse += e * e
    const previous = level
    level = f + alpha * e
    trend = phi * trend + beta * (level - previous - phi * trend)
  }
  return { sse, next: level + phi * trend }
}

/** Additive seasonal smoothing without trend, ETS(A,N,A), started from the first year's monthly deviations. */
function seasonal(y: number[], alpha: number, gamma: number) {
  let level = mean(y.slice(0, SEASON))
  const s = y.slice(0, SEASON).map((v) => v - level)
  let sse = 0
  for (let t = SEASON; t < y.length; t++) {
    const f = level + s[t % SEASON]
    const e = y[t] - f
    sse += e * e
    const nextLevel = level + alpha * e
    s[t % SEASON] += gamma * (y[t] - nextLevel - s[t % SEASON])
    level = nextLevel
  }
  return { sse, next: level + s[y.length % SEASON] }
}

const ALPHAS = [0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9, 1]
const HOLT_GRID: [number, number, number][] = []
for (const a of [0.1, 0.2, 0.3, 0.5, 0.8]) for (const b of [0.05, 0.1, 0.2]) for (const p of [0.8, 0.9, 0.98]) HOLT_GRID.push([a, b, p])
const SEASONAL_GRID: [number, number][] = []
for (const a of [0.1, 0.3, 0.5]) for (const g of [0.05, 0.1, 0.3]) SEASONAL_GRID.push([a, g])

function best<T>(grid: T[], fit: (p: T) => { sse: number; next: number }) {
  let out = fit(grid[0])
  for (let i = 1; i < grid.length; i++) {
    const r = fit(grid[i])
    if (r.sse < out.sse) out = r
  }
  return out.next
}

/** In order of simplicity: when two models score the same, the simpler one is kept. */
export const MODELS: Model[] = [
  { name: 'mean', rung: 1, minLength: 1, next: mean },
  { name: 'naive', rung: 1, minLength: 1, next: (y) => y[y.length - 1] },
  { name: 'moving average (3)', rung: 1, minLength: 3, next: (y) => mean(y.slice(-3)) },
  { name: 'seasonal naive', rung: 1, minLength: SEASON + 1, next: (y) => y[y.length - SEASON] },
  { name: 'exponential smoothing', rung: 2, minLength: 3, next: (y) => best(ALPHAS, (a) => ses(y, a)) },
  { name: 'damped trend', rung: 2, minLength: 4, next: (y) => best(HOLT_GRID, ([a, b, p]) => holt(y, a, b, p)) },
  { name: 'seasonal smoothing', rung: 2, minLength: 2 * SEASON, next: (y) => best(SEASONAL_GRID, ([a, g]) => seasonal(y, a, g)) },
]

export const BASELINES = MODELS.filter((m) => m.rung === 1)

export interface Selected {
  model: string
  forecast: number
  /** The chosen model's one-step errors (actual minus forecast) at the months it was compared on. */
  errors: number[]
}

/** How many recent months the models are compared on, at most. */
const COMPARE_MONTHS = 12
/**
 * A more complex model must beat the simpler one chosen so far by this share of its error to be picked. Twelve
 * months of errors are noisy, and without a margin the choice chases the noise (see evaluation/results/FORECAST.md).
 */
const MARGIN = 0.1
/** Fewest comparison months for a model to take part. */
const MIN_COMPARE = 3

/**
 * Picks the model with the smallest mean absolute one-step error over the last twelve months of y at most (each
 * forecast made only from the months before it), then forecasts the next month with it. A model later in the list
 * replaces an earlier one only if it is clearly better. Short series use the mean.
 */
export function select(y: number[], candidates: Model[] = MODELS): Selected {
  const n = y.length
  if (n === 0) return { model: 'none', forecast: 0, errors: [] }
  const eligible = candidates.filter((m) => m.minLength <= n - MIN_COMPARE)
  if (eligible.length === 0) return { model: 'mean', forecast: mean(y), errors: [] }
  const start = Math.max(n - COMPARE_MONTHS, ...eligible.map((m) => m.minLength))
  let chosen = eligible[0]
  let chosenErrors: number[] = []
  let chosenMae = Infinity
  for (const m of eligible) {
    const errors: number[] = []
    let mae = 0
    for (let t = start; t < n; t++) {
      const e = y[t] - m.next(y.slice(0, t))
      errors.push(e)
      mae += Math.abs(e)
    }
    if (mae < chosenMae * (1 - MARGIN) - 1e-9) {
      chosen = m
      chosenErrors = errors
      chosenMae = mae
    }
  }
  return { model: chosen.name, forecast: chosen.next(y), errors: chosenErrors }
}
