package com.yanyue.rag.domain.port;

import com.yanyue.rag.domain.model.AssistantProfile;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AssistantProfileRepository {
    Optional<AssistantProfile> findPublished(UUID organizationId);
    Optional<AssistantProfile> findDraft(UUID organizationId);
    Optional<AssistantProfile> findById(UUID organizationId, UUID profileId);
    Optional<AssistantProfile> findForConversation(UUID organizationId, UUID conversationId);
    List<AssistantProfile> findVersions(UUID organizationId);
    AssistantProfile saveDraft(AssistantProfile profile);
    AssistantProfile publish(UUID organizationId, UUID profileId);
    void markPreviewed(UUID organizationId, UUID profileId);
}
