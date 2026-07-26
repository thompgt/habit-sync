package dev.thompgt.habitsync.account;

import java.time.Instant;
import java.util.UUID;

public record AppUser(UUID id, String email, String passwordHash, Instant createdAt) {}
