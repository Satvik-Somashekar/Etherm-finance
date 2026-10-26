/**
 * Contracts for the Oracle agent. These mirror the Java records in
 * fi-core/.../core/oracle and the tool schemas in fi-core/src/main/resources/oracle/tools.
 * Dates are ISO yyyy-MM-dd strings; money is a JSON number computed by Java.
 */
import type { FiType } from './fi'

export type Category =
  | 'SALARY' | 'INTEREST' | 'OTHER_CREDIT' | 'RENT' | 'GROCERIES' | 'FOOD' | 'FUEL' | 'SHOPPING'
  | 'UTILITIES' | 'TRANSPORT' | 'CASH_WITHDRAWAL' | 'SIP_DEBIT' | 'INVESTMENT_DEBIT'
  | 'INVESTMENT_CREDIT' | 'PREMIUM_DEBIT' | 'OTHER_DEBIT' | 'MF_PURCHASE' | 'MF_REDEMPTION'
  | 'EQUITY_BUY' | 'EQUITY_SELL' | 'PREMIUM_PAYMENT' | 'EPF_CONTRIBUTION' | 'EPF_INTEREST'
  | 'ACCOUNT_BALANCE' | 'HOLDING_VALUE' | 'POLICY_COVER'

export interface DateRange { from: string; to: string; label?: string }

export interface InstrumentReference {
  isin: string
  symbol: string
  name: string
  sector: string | null
  assetClass: string
  synthetic: boolean
}

export interface EquityLot {
  lotId: string
  accountId: string
  isin: string
  unitsBought: number
  unitsRemaining: number
  purchaseDate: string
  purchasePrice: number
  recordId: string
}

export interface FixedDeposit {
  accountId: string
  institution: string
  principal: number
  annualRatePercent: number
  compoundingPerYear: number
  startDate: string
  maturityDate: string
  recordId: string
}

export type Dimension = 'MONTH' | 'CATEGORY' | 'ACCOUNT' | 'INSTRUMENT' | 'FI_TYPE'
export type Aggregation = 'SUM' | 'COUNT' | 'AVG' | 'MIN' | 'MAX' | 'LATEST' | 'CHANGE' | 'SHARE'

export interface AgentQueryPlan {
  filter?: {
    fiTypes?: FiType[]
    accountIds?: string[]
    instruments?: string[]
    categories?: Category[]
    merchants?: string[]
    dateRange?: DateRange
    amountRange?: { min?: number; max?: number }
  }
  groupBy?: Dimension[]
  aggregate?: Aggregation[]
  compare?: { kind: 'PERIOD_VS_PERIOD' | 'GROUP_VS_GROUP'; against?: DateRange; groupKey?: string }
  rank?: { topN: number; direction?: 'LARGEST' | 'SMALLEST' }
}

export type ResultUnit = 'INR' | 'COUNT' | 'PERCENT' | 'UNITS' | 'MONTHS' | 'DAYS'
export type EmptyReason = 'NO_MATCHING_RECORDS' | 'OUTSIDE_CONSENT' | 'FIELD_MISSING' | 'NOT_IN_DATA'

/** The only form in which a personal figure reaches the model or the UI. */
export interface ResultObject {
  resultId: string
  label: string
  value: number | null
  unit: ResultUnit | null
  period: DateRange | null
  recordIds: string[]
  emptyReason: EmptyReason | null
}

export interface Assumption { name: string; value: string; unit: string | null; adjustable: boolean; source: string }

export type SuggestedActionCode =
  | 'REBALANCE' | 'REDUCE_CONCENTRATION' | 'BUILD_EMERGENCY_BUFFER' | 'REDEPLOY_MATURING_AMOUNT'
  | 'REVIEW_DEPOSIT_COVER' | 'REVIEW_INSURANCE_COVER' | 'CONSIDER_LONG_TERM_TIMING'
  | 'CONSIDER_LOSS_HARVESTING' | 'PUT_IDLE_CASH_TO_WORK' | 'PLAN_FOR_OBLIGATION'

export interface Recommendation {
  ruleId: string
  finding: string
  numbers: ResultObject[]
  assumptions: Assumption[]
  recordIds: string[]
  suggestedAction: { code: SuggestedActionCode; text: string }
  /** Always shown with the recommendation. */
  disclaimer: string
}

export interface Goal {
  goalId: string
  name: string
  targetAmount: number
  targetDate: string
  accountIds: string[]
}

export type GoalStatus = 'ON_TRACK' | 'OFF_TRACK' | 'ACHIEVED' | 'NO_DATA'

export interface GoalProgress {
  goalId: string
  current: ResultObject
  remaining: ResultObject
  requiredMonthly: ResultObject
  status: GoalStatus
}

export type ToolName = 'run_query_plan' | 'find_entities' | 'run_rules' | 'project_scenario' | 'goals'

export interface ToolResult {
  toolCallId: string
  tool: ToolName
  consentIds: string[]
  results: ResultObject[]
  recommendations: Recommendation[]
  goals: GoalProgress[]
  notes: string[]
}
