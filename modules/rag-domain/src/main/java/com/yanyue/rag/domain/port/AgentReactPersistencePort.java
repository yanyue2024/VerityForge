package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.agent.react.ReactCheckpoint;
import com.yanyue.rag.domain.agent.react.ReactKnowledgeReference;
import com.yanyue.rag.domain.agent.react.ReactRankedDocument;
import com.yanyue.rag.domain.agent.react.ReactRecoverableRun;
import com.yanyue.rag.domain.agent.react.ReactRunArtifacts;
import com.yanyue.rag.domain.agent.react.ReactStep;
import com.yanyue.rag.domain.agent.react.ReactToolCall;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AgentReactPersistencePort {
    List<ReactRecoverableRun> findRecoverableRuns();

    void saveCheckpoint(ReactCheckpoint checkpoint);

    Optional<ReactCheckpoint> loadCheckpoint(UUID runId);

    void saveStep(ReactStep step);

    void saveToolCall(ReactToolCall toolCall);

    void saveKnowledgeReference(ReactKnowledgeReference reference);

    void prepareForRecovery(UUID runId);

    Optional<ReactRunArtifacts> loadArtifacts(UUID runId);

    List<ReactRankedDocument> loadDocumentRanking(UUID runId);
}
