# RAG Workbench Web

Vue 3 + TypeScript frontend for the repository's `/api/v1` REST and Run SSE APIs.

## Development

```bash
npm install
npm run dev
```

Vite proxies `/api` to `http://localhost:8080` by default. Override it with
`VITE_DEV_API_TARGET`, or set `VITE_API_BASE_URL` for a separate API origin.

## Verification

```bash
npm run typecheck
npm run build
npm run test:e2e
```

The checked-in Playwright config uses the local Chrome channel. In CI images
without Chrome, install Chromium and remove or override the channel setting.

The Playwright suite covers:

- unavailable login API behavior
- desktop knowledge workspace screenshot and overflow
- mobile chat screenshot, composer usability, and overflow

## Backend coverage

The frontend connects to the existing authentication, knowledge base, document,
conversation, upload, ingestion job, Run cancellation, and authenticated SSE
endpoints. The repository currently has evaluation database tables but no public
evaluation controller, so `/evaluation` intentionally presents an unavailable
state instead of fabricated results.
