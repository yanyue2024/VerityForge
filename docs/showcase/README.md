# VerityForge Showcase

This directory contains the stable visual entry points for the VerityForge portfolio release. The public target is the desktop web experience at `1440x900` or larger.

## Desktop Assets

The screenshots below are generated from deterministic, synthetic browser fixtures so they remain safe to publish and reproducible without a Demo account.

| Asset | Purpose |
| --- | --- |
| `chat-desktop.png` | The primary Chat view with a grounded answer and visible citations |
| `evaluation-desktop.png` | Fast/Deep comparison results and metric cards |
| `knowledgeops-desktop.png` | Knowledge base, document version, metadata, or index-generation workflow |
| `verityforge-demo.webm` | A 30-60 second desktop walkthrough, when a stable recording is available |

The current repository also contains regression snapshots under `web/tests/workbench.spec.ts-snapshots/`. They are implementation snapshots rather than public showcase assets.

Regenerate the desktop screenshots with:

```bash
RAG_CAPTURE_SHOWCASE=true \
PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH=/path/to/chrome \
npm --prefix web run test:e2e -- --project=desktop \
  --grep='desktop knowledge|desktop chat|evaluation workspace restores'
```

## Capture Rules

- Capture desktop web only; do not include mobile layouts in this release.
- Use synthetic documents and conversations that are safe to publish.
- Remove hostnames, ports other than the published demo link, usernames, internal IDs, token counts tied to private accounts, model credentials, and filesystem paths unless they are already part of the public benchmark explanation.
- Keep the browser chrome out of the image and use a consistent viewport and light theme.
- Prefer one useful state per image over a collage of tiny panels.
- Add a short alt text and link each image to the relevant design or benchmark document.

## Demo Boundary

The live preview currently points to `http://idcmnt1.truesight.com.cn:18306`. It is shared and HTTP-only, so it must not be used for private information or production credentials. Screenshots and video remain the canonical portfolio presentation when the endpoint is unavailable.
