FROM python:3.12-slim

ARG INSTALL_EXTRAS=""

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    HOME=/home/parser \
    PARSER_HOST=0.0.0.0 \
    PARSER_PORT=8090

WORKDIR /app

RUN groupadd --system parser \
    && useradd --system --gid parser --create-home --home-dir /home/parser parser

COPY --chown=parser:parser pyproject.toml README.md ./
COPY --chown=parser:parser src ./src

RUN pip install --no-cache-dir ".${INSTALL_EXTRAS}"

USER parser

EXPOSE 8090

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
    CMD ["python", "-c", "import json,urllib.request; assert json.load(urllib.request.urlopen('http://127.0.0.1:8090/health'))['status'] == 'ok'"]

CMD ["sh", "-c", "exec uvicorn parser_sidecar.main:app --host \"${PARSER_HOST}\" --port \"${PARSER_PORT}\""]
