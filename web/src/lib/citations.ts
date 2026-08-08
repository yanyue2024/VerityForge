import type { Citation } from '@/types/api'

export interface IndexedCitation {
  citation: Citation
  rawIndex: number
  displayIndex: number
}

export const EVIDENCE_REFERENCE_PATTERN = /【\s*\[?\s*E?(\d+)\s*\]?\s*】|〔\s*E?(\d+)\s*〕|\[E(\d+)]|\[(\d+)]|\bE(\d+)\b/g

export function evidenceIndex(match: RegExpMatchArray) {
  return Number(match[1] ?? match[2] ?? match[3] ?? match[4] ?? match[5])
}

export function referencedEvidenceIndices(content: string): number[] {
  return [...new Set(
    [...content.matchAll(EVIDENCE_REFERENCE_PATTERN)]
      .map(evidenceIndex)
      .filter((index) => Number.isInteger(index) && index > 0),
  )]
}

export function indexCitations(citations: Citation[]): IndexedCitation[] {
  return citations
    .map((citation, position) => ({
      citation,
      rawIndex: citation.index ?? position + 1,
      position,
    }))
    .sort((left, right) => left.rawIndex - right.rawIndex || left.position - right.position)
    .map(({ citation, rawIndex }, position) => ({
      citation,
      rawIndex,
      displayIndex: position + 1,
    }))
}

export function displayIndexMap(rawIndices: Iterable<number>): Map<number, number> {
  const ordered = [...new Set(rawIndices)]
    .filter((index) => Number.isInteger(index) && index > 0)
    .sort((left, right) => left - right)

  return new Map(ordered.map((rawIndex, position) => [rawIndex, position + 1]))
}

export function filterReferencedCitations(content: string, citations: Citation[]): Citation[] {
  const referenced = new Set(referencedEvidenceIndices(content))
  if (!referenced.size) return []

  return indexCitations(citations)
    .filter((item) => referenced.has(item.rawIndex))
    .map((item) => item.citation.index == null
      ? { ...item.citation, index: item.rawIndex }
      : item.citation)
}
