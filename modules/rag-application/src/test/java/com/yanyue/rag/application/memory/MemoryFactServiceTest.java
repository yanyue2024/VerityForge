package com.yanyue.rag.application.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.yanyue.rag.contract.memory.CreateMemoryFactRequest;
import com.yanyue.rag.contract.memory.MemoryConfirmationStatus;
import com.yanyue.rag.contract.memory.UpdateMemoryFactRequest;
import com.yanyue.rag.domain.model.MemoryFact;
import com.yanyue.rag.domain.port.MemoryFactRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MemoryFactServiceTest {
    private static final Instant NOW = Instant.parse("2026-07-13T08:00:00Z");

    @Test
    void rejectsSourceMessageOutsideCurrentUsersConversations() {
        var repository = new InMemoryRepository(false);
        var service = new MemoryFactService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        var failure = assertThrows(IllegalArgumentException.class, () -> service.create(
                UUID.randomUUID(), UUID.randomUUID(),
                new CreateMemoryFactRequest("偏好简洁回答", 0.8, UUID.randomUUID(), null, null)));

        assertEquals("Source message not found", failure.getMessage());
        assertEquals(0, repository.values.size());
    }

    @Test
    void newFactRequiresExplicitConfirmationBeforePersonalization() {
        var organizationId = UUID.randomUUID();
        var userId = UUID.randomUUID();
        var repository = new InMemoryRepository(true);
        var service = new MemoryFactService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

        var created = service.create(organizationId, userId,
                new CreateMemoryFactRequest("优先使用中文", 0.9, null, NOW.minusSeconds(60), null));
        assertEquals(MemoryConfirmationStatus.INFERRED, created.status());
        assertEquals(List.of(), repository.findConfirmedActive(organizationId, userId, NOW, 20));

        service.update(organizationId, userId, created.id(),
                new UpdateMemoryFactRequest(MemoryConfirmationStatus.CONFIRMED, NOW.plusSeconds(3600)));
        assertEquals(List.of("优先使用中文"), repository.findConfirmedActive(
                organizationId, userId, NOW, 20).stream().map(MemoryFact::factText).toList());
    }

    private static final class InMemoryRepository implements MemoryFactRepository {
        private final List<MemoryFact> values = new ArrayList<>();
        private final boolean sourceOwned;

        private InMemoryRepository(boolean sourceOwned) {
            this.sourceOwned = sourceOwned;
        }

        @Override
        public MemoryFact save(MemoryFact fact) {
            values.removeIf(value -> value.id().equals(fact.id()));
            values.add(fact);
            return fact;
        }

        @Override
        public Optional<MemoryFact> find(UUID organizationId, UUID userId, UUID factId) {
            return values.stream().filter(value -> value.id().equals(factId)
                    && value.organizationId().equals(organizationId) && value.userId().equals(userId)).findFirst();
        }

        @Override
        public List<MemoryFact> findAll(UUID organizationId, UUID userId) {
            return values.stream().filter(value -> value.organizationId().equals(organizationId)
                    && value.userId().equals(userId)).toList();
        }

        @Override
        public List<MemoryFact> findConfirmedActive(UUID organizationId, UUID userId, Instant at, int limit) {
            return findAll(organizationId, userId).stream()
                    .filter(value -> value.status() == MemoryConfirmationStatus.CONFIRMED)
                    .filter(value -> value.validFrom() == null || !value.validFrom().isAfter(at))
                    .filter(value -> value.validTo() == null || value.validTo().isAfter(at))
                    .limit(limit)
                    .toList();
        }

        @Override
        public boolean sourceMessageBelongsTo(UUID organizationId, UUID userId, UUID sourceMessageId) {
            return sourceOwned;
        }

        @Override
        public boolean delete(UUID organizationId, UUID userId, UUID factId) {
            return values.removeIf(value -> value.id().equals(factId)
                    && value.organizationId().equals(organizationId) && value.userId().equals(userId));
        }
    }
}
