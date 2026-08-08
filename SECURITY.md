# Security Policy

VerityForge is published as a source-available portfolio and local-evaluation project. It is not a managed service or a promise that the included configuration is production-ready.

## Reporting a Vulnerability

Please do not disclose suspected vulnerabilities in a public issue. Use GitHub's private vulnerability reporting for this repository when it is available. Otherwise, contact the maintainer through the GitHub profile linked in the README and provide a minimal description so that a private reporting channel can be arranged.

Include the affected component and version, reproduction conditions, expected impact, and whether any public demonstration endpoint is involved. Do not include real credentials, personal data, or confidential documents in a report.

## Demonstration Endpoint

The endpoint linked from the README is a shared, HTTP-only preview. Do not submit sensitive information, reuse production passwords, upload private documents, or perform load testing, automated scanning, destructive testing, or access-control probing against it without explicit written authorization.

Security research should be performed against a local installation using synthetic data. The repository license does not grant permission to test systems or data that you do not own or control.

## Credential Handling

Model API keys are stored as versioned AES-256-GCM envelopes. Profile responses expose only whether a credential exists; neither plaintext credentials nor encrypted key material are returned to the browser. Editing a profile with an empty API-key field preserves the stored credential, while entering a new value replaces it.

Migration V43 deliberately stops when the temporary V42 `model_profile.api_key` column contains data. See the credential migration note in [docs/deployment.md](docs/deployment.md) before upgrading an affected database. Do not bypass the guard by copying plaintext into `encrypted_api_key`.

## Supported Versions

Only the latest public snapshot is considered for security fixes. Historical snapshots and private development branches are not supported public releases.
