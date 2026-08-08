import { expect, test, type Page } from '@playwright/test'

const knowledgeBaseId = '30000000-0000-0000-0000-000000000001'
const session = {
  accessToken: 'upload-format-token',
  expiresAt: '2099-01-01T00:00:00Z',
  userId: '10000000-0000-0000-0000-000000000001',
  organizationId: '20000000-0000-0000-0000-000000000001',
  displayName: '测试用户',
  role: 'ADMIN',
}

async function useSession(page: Page) {
  await page.addInitScript(
    ([key, value]) => window.localStorage.setItem(key, value),
    ['rag-workbench-auth', JSON.stringify(session)],
  )
}

test('upload dialog infers and submits all supported RAG document formats', async ({ page }) => {
  const submittedContentTypes: string[] = []

  await page.route('**/object-upload/*', (route) => route.fulfill({ status: 200, body: '' }))
  await page.route('**/api/v1/**', async (route) => {
    const request = route.request()
    const path = new URL(request.url()).pathname
    if (path === '/api/v1/knowledge-bases') {
      await route.fulfill({
        status: 200,
        json: [{
          id: knowledgeBaseId,
          name: '格式测试知识库',
          description: '验证上传 MIME 推断',
          documentCount: 0,
          chunkCount: 0,
          updatedAt: '2026-07-18T00:00:00Z',
        }],
      })
      return
    }
    if (path === `/api/v1/knowledge-bases/${knowledgeBaseId}/documents`) {
      await route.fulfill({ status: 200, json: [] })
      return
    }
    if (path === `/api/v1/knowledge-bases/${knowledgeBaseId}/metadata-schema`) {
      await route.fulfill({
        status: 200,
        json: {
          id: '92000000-0000-0000-0000-000000000001',
          knowledgeBaseId,
          version: 1,
          fields: [],
          active: true,
          createdAt: '2026-07-18T00:00:00Z',
        },
      })
      return
    }
    if (path.endsWith('/metadata-schema/versions') || path.endsWith('/index-generations')) {
      await route.fulfill({ status: 200, json: [] })
      return
    }
    if (path.endsWith('/documents/upload-intents') && request.method() === 'POST') {
      const body = request.postDataJSON() as { contentType: string }
      submittedContentTypes.push(body.contentType)
      const index = submittedContentTypes.length
      await route.fulfill({
        status: 200,
        json: {
          uploadId: `upload-${index}`,
          documentId: `document-${index}`,
          documentVersionId: `version-${index}`,
          method: 'PUT',
          uploadUrl: `http://127.0.0.1:4173/object-upload/${index}`,
          headers: { 'Content-Type': body.contentType },
          expiresAt: '2099-01-01T00:00:00Z',
        },
      })
      return
    }
    if (/\/api\/v1\/uploads\/upload-\d+\/complete$/.test(path)) {
      const index = submittedContentTypes.length
      await route.fulfill({ status: 200, json: { jobId: `job-${index}`, status: 'QUEUED' } })
      return
    }
    if (/\/api\/v1\/ingestion-jobs\/job-\d+$/.test(path)) {
      await route.fulfill({
        status: 200,
        json: {
          id: path.split('/').pop(),
          status: 'SUCCEEDED',
          currentStage: null,
          attempt: 1,
          maxAttempts: 3,
          errorMessage: null,
          stages: [],
          createdAt: '2026-07-18T00:00:00Z',
          startedAt: '2026-07-18T00:00:00Z',
          completedAt: '2026-07-18T00:00:01Z',
        },
      })
      return
    }
    await route.fulfill({ status: 404, json: { message: `No test route for ${path}` } })
  })

  await useSession(page)
  await page.goto(`/knowledge/${knowledgeBaseId}`)

  const formats = [
    { name: 'manual.pdf', mimeType: 'application/pdf', expected: 'application/pdf' },
    {
      name: 'manual.docx',
      mimeType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
      expected: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
    },
    { name: 'manual.html', mimeType: 'application/octet-stream', expected: 'text/html' },
    { name: 'manual.md', mimeType: 'application/octet-stream', expected: 'text/markdown' },
  ]

  for (const format of formats) {
    await page.getByRole('button', { name: '上传文档' }).first().click()
    await page.getByRole('button', { name: '单份录入' }).click()
    const input = page.locator('input[type="file"][accept*=".html"]')
    await expect(input).toHaveAttribute('accept', /\.html/)
    await expect(input).toHaveAttribute('accept', /\.md/)
    await input.setInputFiles({ name: format.name, mimeType: format.mimeType, buffer: Buffer.from('中文测试') })
    await page.getByRole('button', { name: '上传并入库' }).click()
    await expect(page.getByText('1 份文档已进入处理队列')).toBeVisible()
    await page.getByRole('button', { name: '完成' }).click()
  }

  expect(submittedContentTypes).toEqual(formats.map((format) => format.expected))
})
