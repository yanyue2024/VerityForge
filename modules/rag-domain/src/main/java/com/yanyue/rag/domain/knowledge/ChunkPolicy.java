package com.yanyue.rag.domain.knowledge;

public record ChunkPolicy(
        int parentTargetTokens,
        int parentMaxTokens,
        int parentOverlapTokens,
        int childTargetTokens,
        int childMaxTokens,
        int childOverlapTokens,
        String version
) {
    public ChunkPolicy {
        if (parentTargetTokens < childTargetTokens || parentTargetTokens > parentMaxTokens) {
            throw new IllegalArgumentException("Parent token targets are inconsistent");
        }
        if (parentOverlapTokens < 0 || parentOverlapTokens >= parentTargetTokens) {
            throw new IllegalArgumentException("Parent overlap must be smaller than the parent target");
        }
        if (childTargetTokens <= 0 || childTargetTokens > childMaxTokens) {
            throw new IllegalArgumentException("Child token targets are inconsistent");
        }
        if (childOverlapTokens < 0 || childOverlapTokens >= childTargetTokens) {
            throw new IllegalArgumentException("Child overlap must be smaller than the child target");
        }
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("Chunk policy version is required");
        }
    }

    public static ChunkPolicy defaults() {
        return new ChunkPolicy(1_000, 1_200, 100, 250, 384, 40, "parent-child-250-1000-v8");
    }
}
