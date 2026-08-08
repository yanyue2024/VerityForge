import { errorFromResponse, getStoredAccessToken, resolveApiUrl } from '@/lib/api'
import type { StreamEvent, StreamEventType } from '@/types/api'

const terminalEvents = new Set<StreamEventType>([
  'RUN_COMPLETED',
  'RUN_FAILED',
  'RUN_CANCELLED',
])

interface StreamOptions {
  after?: number
  channel?: 'raw' | 'chat'
  signal?: AbortSignal
  onEvent: (event: StreamEvent) => void
}

function wait(milliseconds: number, signal?: AbortSignal) {
  return new Promise<void>((resolve, reject) => {
    const timer = window.setTimeout(resolve, milliseconds)
    signal?.addEventListener(
      'abort',
      () => {
        window.clearTimeout(timer)
        reject(new DOMException('Aborted', 'AbortError'))
      },
      { once: true },
    )
  })
}

function parseBlock(block: string): StreamEvent | null {
  const data = block
    .split('\n')
    .filter((line) => line.startsWith('data:'))
    .map((line) => line.slice(5).trimStart())
    .join('\n')

  if (!data) return null
  try {
    return JSON.parse(data) as StreamEvent
  } catch {
    return null
  }
}

export async function streamRunEvents(runId: string, options: StreamOptions) {
  let after = options.after ?? 0
  let emptyReconnects = 0
  const resource = options.channel === 'chat' ? 'chat-events' : 'events'

  while (!options.signal?.aborted) {
    const token = getStoredAccessToken()
    const headers = new Headers({ Accept: 'text/event-stream' })
    if (token) headers.set('Authorization', `Bearer ${token}`)

    let response: Response
    try {
      response = await fetch(resolveApiUrl(`/api/v1/runs/${runId}/${resource}?after=${after}`), {
        headers,
        signal: options.signal,
      })
    } catch (error) {
      if (options.signal?.aborted) return
      if (emptyReconnects >= 2) throw error
      emptyReconnects += 1
      await wait(600 * emptyReconnects, options.signal)
      continue
    }

    if (!response.ok) throw await errorFromResponse(response)
    if (!response.body) throw new Error('浏览器未收到事件流')

    const reader = response.body.getReader()
    const decoder = new TextDecoder()
    let buffer = ''
    let received = false
    let terminal = false

    while (!terminal && !options.signal?.aborted) {
      const { done, value } = await reader.read()
      if (done) break
      buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n')

      let boundary = buffer.indexOf('\n\n')
      while (boundary >= 0) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        const event = parseBlock(block)
        if (event && event.sequence > after) {
          received = true
          after = event.sequence
          options.onEvent(event)
          terminal = terminalEvents.has(event.type)
        }
        boundary = buffer.indexOf('\n\n')
      }
    }

    if (terminal || options.signal?.aborted) return
    emptyReconnects = received ? 0 : emptyReconnects + 1
    if (emptyReconnects > 2) throw new Error('事件流已断开，暂时没有新的运行事件')
    await wait(500, options.signal)
  }
}
