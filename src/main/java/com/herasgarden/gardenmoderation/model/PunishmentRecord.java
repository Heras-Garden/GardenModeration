package com.herasgarden.gardenmoderation.model;

import java.util.UUID;

public record PunishmentRecord(
        UUID id,
        UUID playerId,
        String playerName,
        ModerationActionType action,
        String reason,
        String sourceKind,
        String sourceId,
        long createdAt,
        Long expiresAt,
        Long revokedAt,
        String revokedBy
) {
    public boolean active(long now) {
        return revokedAt == null && (expiresAt == null || expiresAt > now);
    }

    public boolean permanent() {
        return expiresAt == null && (action == ModerationActionType.BAN || action == ModerationActionType.MUTE);
    }
}
