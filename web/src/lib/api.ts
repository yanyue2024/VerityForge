import type { ApiErrorBody } from '@/types/api'

export const AUTH_STORAGE_KEY = 'rag-workbench-auth'

const apiBase = (import.meta.env.VITE_API_BASE_URL ?? '').replace(/\/$/, '')

export class ApiClientError extends Error {
  readonly status: number
  readonly code?: string
  readonly fields: ApiErrorBody['fields']

  constructor(message: string, status: number, body?: ApiErrorBody) {
    super(message)
    this.name = 'ApiClientError'
    this.status = status
    this.code = body?.code
    this.fields = body?.fields
  }
}

export function resolveApiUrl(path: string) {
  if (/^https?:\/\//.test(path)) return path
  return `${apiBase}${path.startsWith('/') ? path : `/${path}`}`
}

export function getStoredAccessToken() {
  for (const storage of [localStorage, sessionStorage]) {
    const raw = storage.getItem(AUTH_STORAGE_KEY)
    if (!raw) continue
    try {
      const parsed = JSON.parse(raw) as { accessToken?: string }
      if (parsed.accessToken) return parsed.accessToken
    } catch {
      storage.removeItem(AUTH_STORAGE_KEY)
    }
  }
  return null
}

export async function errorFromResponse(response: Response) {
  let body: ApiErrorBody | undefined
  try {
    body = (await response.json()) as ApiErrorBody
  } catch {
    body = undefined
  }

  if (response.status === 401) {
    window.dispatchEvent(new CustomEvent('rag:auth-expired'))
  }

  const fallback = response.status >= 500 ? '服务暂时不可用，请稍后重试' : `请求失败（${response.status}）`
  return new ApiClientError(body?.message || fallback, response.status, body)
}

interface RequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown
  authenticated?: boolean
}

async function request<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const headers = new Headers(options.headers)
  headers.set('Accept', 'application/json')

  if (options.authenticated !== false) {
    const token = getStoredAccessToken()
    if (token) headers.set('Authorization', `Bearer ${token}`)
  }

  let body: BodyInit | undefined
  if (options.body !== undefined) {
    if (
      options.body instanceof FormData ||
      options.body instanceof Blob ||
      typeof options.body === 'string'
    ) {
      body = options.body
    } else {
      headers.set('Content-Type', 'application/json')
      body = JSON.stringify(options.body)
    }
  }

  let response: Response
  try {
    response = await fetch(resolveApiUrl(path), {
      ...options,
      headers,
      body,
    })
  } catch {
    throw new ApiClientError('无法连接 API，请确认后端服务已启动', 0)
  }

  if (!response.ok) throw await errorFromResponse(response)
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

export const api = {
  get: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'GET' }),
  post: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'POST', body }),
  put: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'PUT', body }),
  patch: <T>(path: string, body?: unknown, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'PATCH', body }),
  delete: <T>(path: string, options?: RequestOptions) =>
    request<T>(path, { ...options, method: 'DELETE' }),
}

export function readableError(error: unknown) {
  if (error instanceof ApiClientError) return error.message
  if (error instanceof Error) return error.message
  return '发生未知错误'
}
