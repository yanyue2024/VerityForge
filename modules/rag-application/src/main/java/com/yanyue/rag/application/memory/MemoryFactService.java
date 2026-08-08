package com.yanyue.rag.application.memory;

import com.yanyue.rag.contract.memory.CreateMemoryFactRequest;
import com.yanyue.rag.contract.memory.MemoryFactView;
import com.yanyue.rag.contract.memory.UpdateMemoryFactRequest;
import com.yanyue.rag.domain.model.MemoryFact;
import com.yanyue.rag.domain.port.MemoryFactRepository;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class MemoryFactService {
    private final MemoryFactRepository repository;
    private final Clock clock;

    public MemoryFactService(MemoryFactRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public List<MemoryFactView> list(UUID organizationId, UUID userId) {
        return repository.findAll(organizationId, userId).stream().map(this::view).toList();
    }

    public MemoryFactView create(UUID organizationId, UUID userId, CreateMemoryFactRequest request) {
        validateRange(request.validFrom(), request.validTo());
        if (request.sourceMessageId() != null
                && !repository.sourceMessageBelongsTo(organizationId, userId, request.sourceMessageId())) {
            throw new IllegalArgumentException("Source message not found");
        }
        var now = clock.instant();
        return view(repository.save(new MemoryFact(
                UUID.randomUUID(), organizationId, userId, request.factText().strip(), request.sourceMessageId(),
                request.confidence(), com.yanyue.rag.contract.memory.MemoryConfirmationStatus.INFERRED,
                request.validFrom(), request.validTo(), now, now
        )));
    }

    public MemoryFactView update(
            UUID organizationId,
            UUID userId,
            UUID factId,
            UpdateMemoryFactRequest request
    ) {
        var existing = repository.find(organizationId, userId, factId)
                .orElseThrow(() -> new IllegalArgumentException("Memory Fact not found"));
        validateRange(existing.validFrom(), request.validTo());
        return view(repository.save(new MemoryFact(
                existing.id(), existing.organizationId(), existing.userId(), existing.factText(),
                existing.sourceMessageId(), existing.confidence(), request.status(), existing.validFrom(),
                request.validTo(), existing.createdAt(), clock.instant()
        )));
    }

    public void delete(UUID organizationId, UUID userId, UUID factId) {
        if (!repository.delete(organizationId, userId, factId)) {
            throw new IllegalArgumentException("Memory Fact not found");
        }
    }

    private void validateRange(java.time.Instant from, java.time.Instant to) {
        if (from != null && to != null && !to.isAfter(from)) {
            throw new IllegalArgumentException("validTo must be later than validFrom");
        }
    }

    private MemoryFactView view(MemoryFact fact) {
        return new MemoryFactView(fact.id(), fact.factText(), fact.sourceMessageId(), fact.confidence(), fact.status(),
                fact.validFrom(), fact.validTo(), fact.createdAt(), fact.updatedAt());
    }
}
