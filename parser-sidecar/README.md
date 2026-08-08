# Parser Sidecar

Python 3.12 FastAPI service that downloads a document from a presigned URL and
returns the repository's `NormalizedDocument` contract. If `resultUrl` is
provided, the same JSON result is also uploaded with HTTP `PUT`.

## Supported formats

| Format | Default engine | Notes |
| --- | --- | --- |
| PDF | `PyMuPDF` | Layout-aware text blocks; page numbers and bounding boxes are retained |
| DOCX | `python-docx` | Paragraph order, headings, lists, and tables |
| XLSX | `openpyxl` | One heading per sheet and one table per contiguous row region |
| HTML | `lxml` | Headings, paragraphs, lists, code, captions, and tables; active/navigation elements are removed |
| Markdown/text | built in | Headings, lists, code fences, and Markdown tables |

The `DOCLING` parser profile is available only when the `docling` extra is
installed. It is intentionally excluded from the default dependencies and base
Docker image. `AUTO`, `LIGHTWEIGHT`, `FAST`, and `DEFAULT` all select the
lightweight engines above, so PDF parsing prefers `PyMuPDF` and falls back to `pypdf` only when unavailable.

## API

`GET /health` reports service and optional Docling availability.

`POST /v1/parse` accepts the camel-case fields from
`modules/rag-contract/.../ParseDocumentRequest.java`:

```json
{
  "sourceUrl": "https://object-store.example/document.pdf?signature=...",
  "resultUrl": "https://object-store.example/result.json?signature=...",
  "fileName": "document.pdf",
  "contentType": "application/pdf",
  "parserProfile": "AUTO",
  "options": {}
}
```

`resultUrl`, `fileName`, and `contentType` may be omitted. The source name and
media type are inferred from response headers, URL path, file extension, and
file signatures when possible.

Useful options:

- `dataOnly`: read cached XLSX formula results; defaults to `true`.
- `includeHiddenSheets`: include hidden XLSX sheets; defaults to `false`.
- `maxRowsPerSheet`: XLSX row cap; defaults to `10000`, maximum `100000`.
- `maxColumns`: XLSX column cap; defaults to `256`, maximum `16384`.
- `encoding`: explicit encoding for plain-text input; defaults to BOM-aware UTF-8.

## Normalization

- `contentHash` is the lowercase SHA-256 hash of the downloaded source bytes.
- Every V2 block has a deterministic `blk_v2_...` ID.
- Every block's `attributes` contains `blockVersion` and its text
  `contentHash`.
- `sourceStart` is inclusive and `sourceEnd` is exclusive.
- Offsets use UTF-16 code units in the canonical normalized text formed by
  joining blocks in `orderIndex` order with two newline characters.
- `headingPath` includes the document title and current nested headings.
- Tables are emitted as readable GitHub-flavored Markdown.
- Document metadata includes the normalized-text hash and the offset basis.
- The V2 response includes immutable normalized Markdown plus a PASS/WARNING/FAIL quality report.

## Development

```bash
cd parser-sidecar
uv sync --extra test
uv run pytest
uv run ruff check .
uv build
```

Run locally:

```bash
uv run uvicorn parser_sidecar.main:app --host 0.0.0.0 --port 8090
```

Build the lightweight image:

```bash
docker build -t rag-parser-sidecar .
```

Build an image with Docling:

```bash
docker build --build-arg 'INSTALL_EXTRAS=[docling]' -t rag-parser-sidecar:docling .
```

## Configuration

| Environment variable | Default |
| --- | --- |
| `PARSER_HOST` | `0.0.0.0` |
| `PARSER_PORT` | `8090` |
| `PARSER_LOG_LEVEL` | `INFO` |
| `PARSER_HTTP_TIMEOUT_SECONDS` | `60` |
| `PARSER_MAX_DOWNLOAD_BYTES` | `67108864` |
| `PARSER_MAX_ARCHIVE_BYTES` | `268435456` |
