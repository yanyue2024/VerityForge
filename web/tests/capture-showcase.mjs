import { mkdir } from 'node:fs/promises'
import { resolve } from 'node:path'
import { chromium } from '@playwright/test'

const baseUrl = process.env.RAG_SHOWCASE_BASE_URL
const username = process.env.RAG_SHOWCASE_USERNAME
const password = process.env.RAG_SHOWCASE_PASSWORD
const accessToken = process.env.RAG_SHOWCASE_ACCESS_TOKEN
const outputDir = resolve(process.env.RAG_SHOWCASE_OUTPUT_DIR ?? '../docs/showcase')

if (!baseUrl || (!accessToken && (!username || !password))) {
  throw new Error(
    'RAG_SHOWCASE_BASE_URL and either RAG_SHOWCASE_ACCESS_TOKEN or login credentials are required',
  )
}

await mkdir(outputDir, { recursive: true })

const browser = await chromium.launch({
  headless: true,
  ...(process.env.RAG_SHOWCASE_BROWSER_PATH
    ? { executablePath: process.env.RAG_SHOWCASE_BROWSER_PATH }
    : {}),
})
const context = await browser.newContext({
  viewport: { width: 2048, height: 1080 },
  deviceScaleFactor: 1,
  colorScheme: 'light',
  reducedMotion: 'reduce',
})
const page = await context.newPage()
const captured = []

function decodeJwtPayload(token) {
  const parts = token.split('.')
  if (parts.length !== 3) throw new Error('RAG_SHOWCASE_ACCESS_TOKEN must be a JWT')
  try {
    return JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8'))
  } catch (error) {
    throw new Error('RAG_SHOWCASE_ACCESS_TOKEN has an unreadable payload', { cause: error })
  }
}

async function goto(path) {
  await page.goto(new URL(path, baseUrl).toString(), { waitUntil: 'networkidle' })
}

async function capture(fileName, fullPage = false) {
  await page.screenshot({ path: resolve(outputDir, fileName), fullPage })
  captured.push(fileName)
}

async function openConversation(title, fileName) {
  await goto('/chat')
  await page.evaluate(() => {
    localStorage.setItem('verityforge.chatSidebarCollapsed', 'false')
    localStorage.setItem('verityforge.chatHistoryExpanded', 'true')
    localStorage.setItem('verityforge.chatHistoryCollapsedGroups', '[]')
  })
  await page.reload({ waitUntil: 'networkidle' })

  const conversation = page.locator('#conversation-history a[title]').filter({ hasText: title }).first()
  if (!(await conversation.count())) return false

  await conversation.click()
  await page.waitForURL(/\/chat\?conversation=/)
  await page.getByRole('heading', { name: title, exact: true }).waitFor()
  await page.waitForTimeout(600)

  const evidenceButton = page.locator('header button[aria-label^="查看证据"]')
  if (await evidenceButton.count()) {
    await evidenceButton.click()
    await page.getByTestId('citation-panel').waitFor()
    await page.waitForTimeout(500)
  }

  await capture(fileName)
  return true
}

try {
  if (accessToken) {
    const claims = decodeJwtPayload(accessToken)
    const userId = String(claims.sub ?? '')
    const organizationId = String(claims.org ?? claims.organizationId ?? '')
    const role = String(claims.role ?? '')
    if (!userId || !organizationId || !role) {
      throw new Error('JWT must contain sub, org/organizationId, and role claims')
    }
    const expiresAt = claims.exp
      ? new Date(Number(claims.exp) * 1_000).toISOString()
      : new Date(Date.now() + 60 * 60 * 1_000).toISOString()
    const displayName = process.env.RAG_SHOWCASE_DISPLAY_NAME
      ?? String(claims.username ?? claims.preferred_username ?? userId)
    await page.addInitScript((session) => {
      localStorage.setItem('rag-workbench-auth', JSON.stringify({
        accessToken: session.accessToken,
        expiresAt: session.expiresAt,
        userId: session.userId,
        organizationId: session.organizationId,
        displayName: session.displayName,
        role: session.role,
      }))
    }, { accessToken, expiresAt, userId, organizationId, displayName, role })
    await goto('/chat')
  } else {
    await goto('/login')
    await page.getByLabel('用户名').fill(username)
    await page.getByLabel('密码').fill(password)
    await page.getByRole('button', { name: '进入工作台' }).click()
    await page.waitForURL(/\/chat(?:\?|$)/)
  }

  const requestedConversations = [
    ['快速模式', 'chat-fast-evidence.png'],
    ['深度模式', 'chat-deep-evidence.png'],
    ['自动模式', 'chat-auto-evidence.png'],
  ]
  const conversationResults = {}
  for (const [title, fileName] of requestedConversations) {
    conversationResults[title] = await openConversation(title, fileName)
  }

  await goto('/knowledge')
  await page.getByRole('heading', { name: '知识库', exact: true }).waitFor()
  await capture('knowledge-bases.png')

  const knowledgeLink = page.getByRole('link', { name: /中文企业技术知识库 v1/ }).first()
  if (await knowledgeLink.count()) {
    await knowledgeLink.click()
    await page.getByRole('heading', { name: '中文企业技术知识库 v1', exact: true }).waitFor()
    await page.getByRole('button', { name: /检索可用/ }).waitFor({ timeout: 15_000 })
    await capture('knowledge-documents.png')

    const chunkButton = page.getByRole('button', { name: /子块.*父块/ }).first()
    if (await chunkButton.count()) {
      await chunkButton.click()
      await page.getByRole('button', { name: '原文预览', exact: true }).waitFor()
      await page.waitForTimeout(500)
      await capture('document-original.png')

      await page.getByRole('button', { name: '解析正文', exact: true }).click()
      await page.waitForTimeout(500)
      await capture('document-parsed.png')

      await page.getByRole('button', { name: '检索分块', exact: true }).click()
      await page.getByRole('heading', { name: '检索分块', exact: true }).waitFor()
      await page.waitForTimeout(500)
      await capture('document-chunks.png')

      await page.getByRole('button', { name: 'Metadata', exact: true }).click()
      await page.getByRole('heading', { name: '文档 Metadata', exact: true }).waitFor()
      await page.waitForTimeout(500)
      await capture('document-metadata.png')

      await page.getByRole('button', { name: '处理过程', exact: true }).click()
      await page.getByRole('heading', { name: '处理过程', exact: true }).waitFor()
      await page.waitForTimeout(500)
      await capture('document-processing.png')
    }
  }

  await goto('/evaluation')
  await page.getByRole('heading', { name: '评测', exact: true }).waitFor()
  await page.waitForTimeout(700)
  await capture('evaluation-fast-deep.png')

  const pinnedTitles = await page.evaluate(async (url) => {
    const response = await fetch(url, {
      headers: {
        Authorization: `Bearer ${JSON.parse(localStorage.getItem('rag-workbench-auth') ?? '{}').accessToken ?? ''}`,
      },
    })
    if (!response.ok) return []
    const body = await response.json()
    const items = Array.isArray(body) ? body : body.items ?? []
    return items.filter((item) => item.pinned).map((item) => item.title)
  }, new URL('/api/v1/conversations?limit=30', baseUrl).toString())

  console.log(JSON.stringify({ captured, conversationResults, pinnedTitles }, null, 2))
} finally {
  await browser.close()
}
