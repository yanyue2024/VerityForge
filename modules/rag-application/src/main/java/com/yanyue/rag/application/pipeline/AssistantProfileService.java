package com.yanyue.rag.application.pipeline;

import com.yanyue.rag.contract.pipeline.AssistantProfileView;
import com.yanyue.rag.contract.pipeline.UpdateAssistantProfileRequest;
import com.yanyue.rag.domain.model.AssistantProfile;
import com.yanyue.rag.domain.port.AssistantProfileRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AssistantProfileService {
    private final AssistantProfileRepository repository;
    private final Clock clock;

    public AssistantProfileService(AssistantProfileRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public AssistantProfile published(UUID organizationId) {
        return repository.findPublished(organizationId)
                .orElseThrow(() -> new IllegalStateException("No published assistant role is configured"));
    }

    public AssistantProfile find(UUID organizationId, UUID profileId) {
        return repository.findById(organizationId, profileId)
                .orElseThrow(() -> new IllegalArgumentException("Assistant role version was not found"));
    }

    public AssistantProfile forConversation(UUID organizationId, UUID conversationId) {
        return repository.findForConversation(organizationId, conversationId)
                .orElseGet(() -> published(organizationId));
    }

    public AssistantProfileView draftOrPublished(UUID organizationId) {
        return view(repository.findDraft(organizationId).orElseGet(() -> published(organizationId)));
    }

    public AssistantProfileView saveDraft(UUID organizationId, UpdateAssistantProfileRequest request) {
        var now = clock.instant();
        var draft = new AssistantProfile(UUID.randomUUID(), organizationId, 1, AssistantProfile.Status.DRAFT,
                request.assistantName().strip(), request.identity().strip(), clean(request.capabilities()),
                request.tone().strip(), clean(request.boundaries()),
                request.additionalInstructions() == null ? "" : request.additionalInstructions().strip(),
                null, null, now, now);
        return view(repository.saveDraft(draft));
    }

    public AssistantProfileView markPreviewed(UUID organizationId, UUID profileId) {
        repository.markPreviewed(organizationId, profileId);
        return view(find(organizationId, profileId));
    }

    public AssistantProfileView publish(UUID organizationId, UUID profileId) {
        var draft = find(organizationId, profileId);
        if (draft.status() != AssistantProfile.Status.DRAFT || draft.previewedAt() == null) {
            throw new IllegalArgumentException("Test the latest assistant role draft before publishing");
        }
        return view(repository.publish(organizationId, profileId));
    }

    public List<AssistantProfileView> versions(UUID organizationId) {
        return repository.findVersions(organizationId).stream().map(this::view).toList();
    }

    public boolean restoreAsDraft(UUID organizationId, UUID profileId) {
        var source = repository.findById(organizationId, profileId).orElse(null);
        if (source == null) return false;
        var request = new UpdateAssistantProfileRequest(source.assistantName(), source.identity(),
                source.capabilities(), source.tone(), source.boundaries(), source.additionalInstructions());
        saveDraft(organizationId, request);
        return true;
    }

    public void clonePublishedAsDraft(UUID organizationId) {
        restoreAsDraft(organizationId, published(organizationId).id());
    }

    private List<String> clean(List<String> values) {
        return values.stream().map(String::strip).filter(value -> !value.isBlank()).toList();
    }

    private AssistantProfileView view(AssistantProfile value) {
        return new AssistantProfileView(value.id(), value.version(), value.status().name(), value.assistantName(),
                value.identity(), value.capabilities(), value.tone(), value.boundaries(),
                value.additionalInstructions(), value.previewedAt(), value.publishedAt(),
                value.createdAt(), value.updatedAt());
    }
}
