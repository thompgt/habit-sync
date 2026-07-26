package dev.thompgt.habitsync.replication;

import dev.thompgt.habitsync.replication.dto.SyncDtos;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncRequest;
import dev.thompgt.habitsync.replication.dto.SyncDtos.SyncResponse;
import dev.thompgt.habitsync.security.AuthenticatedUser;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The only endpoint clients use for data.
 *
 * <p>There are deliberately no CRUD endpoints for habits or workouts. Every write goes
 * through the replication log, because {@code server_seq} must be allocated in exactly
 * one place — a second write path silently reintroduces the ADR-002 data-loss bug.
 */
@RestController
@RequestMapping("/v1/sync")
public class SyncController {

    private final SyncService syncService;

    public SyncController(SyncService syncService) {
        this.syncService = syncService;
    }

    /**
     * Push and pull in one round trip.
     *
     * <p>A client with nothing to push sends an empty {@code ops} list, which makes this
     * a pull. Combining them halves the request count on the common path, where a device
     * has a couple of local edits and wants whatever it missed.
     */
    @PostMapping
    public ResponseEntity<SyncResponse> sync(
            @AuthenticationPrincipal AuthenticatedUser principal, @Valid @RequestBody SyncRequest request) {

        // Reject unknown protocol versions rather than guessing. A client too new to
        // understand gets a clear signal to upgrade instead of writing ops this server
        // would misinterpret and log permanently.
        if (request.protocolVersion() == null || request.protocolVersion() != SyncDtos.PROTOCOL_VERSION) {
            return ResponseEntity.status(HttpStatus.UPGRADE_REQUIRED).build();
        }

        return ResponseEntity.ok(syncService.sync(principal, request));
    }

    /**
     * Pull-only, for paging through a backlog and for background refresh.
     *
     * <p>Equivalent to a POST with no ops, but expressible as a plain GET so it is
     * cacheable-shaped and safe to retry without a body.
     */
    @GetMapping
    public SyncResponse pull(
            @AuthenticationPrincipal AuthenticatedUser principal,
            @RequestParam(defaultValue = "0") long sinceSeq) {

        return syncService.sync(
                principal, new SyncRequest(sinceSeq, SyncDtos.PROTOCOL_VERSION, java.util.List.of()));
    }
}
