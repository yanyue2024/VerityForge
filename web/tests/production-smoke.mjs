import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from '@playwright/test'

const baseUrl = process.env.RAG_SMOKE_BASE_URL
const username = process.env.RAG_SMOKE_USERNAME
const password = process.env.RAG_SMOKE_PASSWORD
const outputDir = resolve(process.env.RAG_SMOKE_OUTPUT_DIR ?? '../tmp/playwright')

if (!baseUrl || !username || !password) {
  throw new Error(
    'RAG_SMOKE_BASE_URL, RAG_SMOKE_USERNAME and RAG_SMOKE_PASSWORD are required',
  )
}

await mkdir(outputDir, { recursive: true })

const browser = await chromium.launch({ headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
const page = await context.newPage()
const errors = []

page.on('console', (message) => {
  if (message.type() === 'error') errors.push(`console: ${message.text()}`)
})
page.on('pageerror', (error) => errors.push(`page: ${error.message}`))

async function goto(path) {
  await page.goto(new URL(path, baseUrl).toString(), { waitUntil: 'networkidle' })
}

async function overflow() {
  return page.evaluate(() => {
    const width = Math.max(document.documentElement.scrollWidth, document.body.scrollWidth)
    return Math.max(0, width - window.innerWidth)
  })
}

try {
  await goto('/login')
  await page.getByLabel('用户名').fill(username)
  await page.getByLabel('密码').fill(password)
  await page.getByRole('button', { name: '进入工作台' }).click()
  await page.waitForURL(/\/chat(?:\?|$)/)

  await goto('/knowledge')
  await page.getByRole('heading', { name: '知识库', exact: true }).waitFor()
  const knowledgeOverflow = await overflow()
  await page.screenshot({
    path: resolve(outputDir, 'production-knowledge-desktop.png'),
    fullPage: true,
  })

  await goto('/team')
  await page.getByRole('heading', { name: '团队成员', exact: true }).waitFor()
  const teamOverflow = await overflow()
  await page.screenshot({
    path: resolve(outputDir, 'production-team-desktop.png'),
    fullPage: true,
  })

  await goto('/security')
  await page.getByRole('heading', { name: '凭据安全', exact: true }).waitFor()
  const securityOverflow = await overflow()
  await page.screenshot({
    path: resolve(outputDir, 'production-security-desktop.png'),
    fullPage: true,
  })

  await goto('/chat')
  await page.getByRole('button', { name: '过滤', exact: true }).click()
  await page.getByText('Metadata 过滤', { exact: true }).waitFor()
  const chatFilterOverflow = await overflow()
  await page.screenshot({
    path: resolve(outputDir, 'production-chat-filter-desktop.png'),
    fullPage: true,
  })

  await goto('/evaluation')
  await page.getByRole('heading', { name: '评测', exact: true }).waitFor()
  const evaluationOverflow = await overflow()
  await page.screenshot({
    path: resolve(outputDir, 'production-evaluation-desktop.png'),
    fullPage: true,
  })

  await page.setViewportSize({ width: 390, height: 844 })
  await goto('/memory')
  await page.getByRole('heading', { name: '长期记忆', exact: true }).waitFor()
  const memoryOverflow = await overflow()
  await page.screenshot({
    path: resolve(outputDir, 'production-memory-mobile.png'),
    fullPage: true,
  })

  await goto('/team')
  await page.getByRole('heading', { name: '团队成员', exact: true }).waitFor()
  const teamMobileOverflow = await overflow()
  await page.screenshot({
    path: resolve(outputDir, 'production-team-mobile.png'),
    fullPage: true,
  })

  await goto('/security')
  await page.getByRole('heading', { name: '凭据安全', exact: true }).waitFor()
  const securityMobileOverflow = await overflow()
  await page.screenshot({
    path: resolve(outputDir, 'production-security-mobile.png'),
    fullPage: true,
  })

  await goto('/evaluation')
  await page.getByRole('heading', { name: '评测', exact: true }).waitFor()
  const evaluationMobileOverflow = await overflow()
  await page.screenshot({
    path: resolve(outputDir, 'production-evaluation-mobile.png'),
    fullPage: true,
  })

  const result = {
    knowledgeOverflow,
    teamOverflow,
    securityOverflow,
    chatFilterOverflow,
    memoryOverflow,
    teamMobileOverflow,
    securityMobileOverflow,
    evaluationOverflow,
    evaluationMobileOverflow,
    errors,
  }
  console.log(JSON.stringify(result, null, 2))

  if (
    knowledgeOverflow ||
    teamOverflow ||
    securityOverflow ||
    chatFilterOverflow ||
    memoryOverflow ||
    teamMobileOverflow ||
    securityMobileOverflow ||
    evaluationOverflow ||
    evaluationMobileOverflow ||
    errors.length
  ) {
    process.exitCode = 1
  }
} finally {
  await browser.close()
}
