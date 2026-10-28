#!/usr/bin/env node
// Records the services' real responses for demo mode: node scripts/record-demo.mjs
// Run with the four services up (ETHERM_AS_OF=2026-10-25) and the synthetic data loaded. Signs in as each
// customer with ETHERM_DEMO_PASSCODE (default etherm-demo). Answers are recorded without their turn signature,
// which only the live services can check.
import { mkdirSync, readFileSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const out = join(here, '..', 'public', 'demo')
const questions = JSON.parse(readFileSync(join(here, '..', 'src', 'demo', 'questions.json'), 'utf8'))
const base = {
  ingestion: process.env.ETHERM_INGESTION_URL ?? 'http://localhost:8081',
  normalization: process.env.ETHERM_NORMALIZATION_URL ?? 'http://localhost:8082',
  rag: process.env.ETHERM_RAG_URL ?? 'http://localhost:8084',
}
const normalise = (q) => q.toLowerCase().replace(/[^a-z0-9 ]+/g, ' ').replace(/\s+/g, ' ').trim()

let token = null
async function json(url, init = {}) {
  const res = await fetch(url, { ...init, headers: { ...(init.headers ?? {}), Authorization: `Bearer ${token}` } })
  if (!res.ok) throw new Error(`${res.status} ${url}`)
  return res.json()
}

mkdirSync(out, { recursive: true })
for (let n = 1; n <= 5; n++) {
  const id = `user${String(n).padStart(3, '0')}@etherm-aa`
  const enc = encodeURIComponent(id)
  const session = await fetch(`${base.ingestion}/api/session`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ customerId: id, passcode: process.env.ETHERM_DEMO_PASSCODE ?? 'etherm-demo' }),
  })
  if (!session.ok) throw new Error(`sign-in for ${id} failed: ${session.status}`)
  token = (await session.json()).token
  const recording = {
    accounts: await json(`${base.normalization}/api/customers/${enc}/accounts`),
    fips: await json(`${base.ingestion}/api/customers/${enc}/fips`),
    overview: await json(`${base.rag}/api/customers/${enc}/overview`),
    answers: {},
  }
  for (const question of questions) {
    const { turnSignature, ...answer } = await json(`${base.rag}/api/query`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ customerId: id, question }),
    })
    void turnSignature
    recording.answers[normalise(question)] = answer
  }
  const file = join(out, `${id.split('@')[0]}.json`)
  writeFileSync(file, JSON.stringify(recording))
  console.log(`${id}: ${questions.length} answers, ${(JSON.stringify(recording).length / 1024).toFixed(0)} KB`)
}
