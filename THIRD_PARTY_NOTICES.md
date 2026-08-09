# Third-Party Notices

## Tencent WeKnora

Early Deep RAG research in this project was informed by Tencent WeKnora's
progressive-search design and ReAct stopping semantics. The current final Deep
pipeline is an independently implemented, bounded Goal/evidence state machine;
there is no WeKnora runtime dependency or copied benchmark content.

Copyright (C) 2025 Tencent. All rights reserved.

WeKnora is distributed under the MIT License:

> Permission is hereby granted, free of charge, to any person obtaining a copy
> of this software and associated documentation files (the "Software"), to deal
> in the Software without restriction, including without limitation the rights
> to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
> copies of the Software, and to permit persons to whom the Software is
> furnished to do so, subject to the following conditions:
>
> The above copyright notice and this permission notice shall be included in
> all copies or substantial portions of the Software.
>
> THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
> IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
> FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
> AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
> LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
> OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
> SOFTWARE.

The existing model-sidecar-specific attribution remains in
`model-sidecar/THIRD_PARTY_NOTICES.md`.

## Chinese Enterprise Technical Knowledge Base v1

The committed 200-document corpus contains transformed material from
openEuler, Kubernetes, Ant Design, and Apache Doris. Those documents remain
under their respective CC BY-SA 4.0, CC BY 4.0, MIT, and Apache-2.0 licenses.
Selection manifests, upstream revisions, checksums, and license copies are in
`data/chinese-enterprise-rag-v1/`. The VerityForge source license does not
replace or narrow those upstream permissions and obligations.
