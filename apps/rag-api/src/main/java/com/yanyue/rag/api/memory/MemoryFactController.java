package com.yanyue.rag.api.memory;

import com.yanyue.rag.api.security.AuthenticatedUser;
import com.yanyue.rag.application.memory.MemoryFactService;
import com.yanyue.rag.contract.memory.CreateMemoryFactRequest;
import com.yanyue.rag.contract.memory.MemoryFactView;
import com.yanyue.rag.contract.memory.UpdateMemoryFactRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/memory-facts")
public class MemoryFactController {
    private final MemoryFactService service;

    public MemoryFactController(MemoryFactService service) {
        this.service = service;
    }

    @GetMapping
    public List<MemoryFactView> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return service.list(user.organizationId(), user.userId());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MemoryFactView create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody CreateMemoryFactRequest request
    ) {
        return service.create(user.organizationId(), user.userId(), request);
    }

    @PatchMapping("/{factId}")
    public MemoryFactView update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable UUID factId,
            @Valid @RequestBody UpdateMemoryFactRequest request
    ) {
        return service.update(user.organizationId(), user.userId(), factId, request);
    }

    @DeleteMapping("/{factId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID factId) {
        service.delete(user.organizationId(), user.userId(), factId);
    }
}
