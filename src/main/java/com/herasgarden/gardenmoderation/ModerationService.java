package com.herasgarden.gardenmoderation;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.integration.IntegrationEventType;
import com.herasgarden.gardenmoderation.model.ModerationActionType;
import com.herasgarden.gardenmoderation.model.PunishmentRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class ModerationService {
    private final JavaPlugin plugin;
    private final GardenPlatform platform;
    private final Map<UUID, PunishmentRecord> activeMutes = new ConcurrentHashMap<>();
    private final Map<UUID, PunishmentRecord> activeBans = new ConcurrentHashMap<>();

    public ModerationService(JavaPlugin plugin, GardenPlatform platform) {
        this.plugin = plugin;
        this.platform = platform;
    }

    public synchronized void refresh() throws SQLException {
        long now = System.currentTimeMillis();
        Map<UUID, PunishmentRecord> mutes = new ConcurrentHashMap<>();
        Map<UUID, PunishmentRecord> bans = new ConcurrentHashMap<>();

        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gm_punishments WHERE revoked_at IS NULL "
                             + "AND (expires_at IS NULL OR expires_at > ?) "
                             + "AND action_type IN ('MUTE','TEMPBAN','BAN') "
                             + "ORDER BY created_at ASC")) {
            statement.setLong(1, now);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    PunishmentRecord record = read(result);
                    if (record.action() == ModerationActionType.MUTE) {
                        mutes.put(record.playerId(), record);
                    } else {
                        bans.put(record.playerId(), record);
                    }
                }
            }
        }

        activeMutes.clear();
        activeMutes.putAll(mutes);
        activeBans.clear();
        activeBans.putAll(bans);
    }

    public void notePlayer(OfflinePlayer player) throws SQLException {
        if (player == null || player.getName() == null || player.getName().isBlank()) {
            return;
        }
        notePlayer(player.getUniqueId(), player.getName());
    }

    public void notePlayer(UUID playerId, String playerName) throws SQLException {
        long now = System.currentTimeMillis();
        try (Connection connection = platform.storage().connection()) {
            int changed;
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE gm_player_directory SET last_name = ?, last_seen_at = ? WHERE player_uuid = ?")) {
                update.setString(1, playerName);
                update.setLong(2, now);
                update.setString(3, playerId.toString());
                changed = update.executeUpdate();
            }
            if (changed == 0) {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO gm_player_directory (player_uuid, last_name, last_seen_at) VALUES (?, ?, ?)")) {
                    insert.setString(1, playerId.toString());
                    insert.setString(2, playerName);
                    insert.setLong(3, now);
                    insert.executeUpdate();
                }
            }
        }
    }

    public Optional<Target> resolveTarget(String token) throws SQLException {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }

        Player online = Bukkit.getPlayerExact(token);
        if (online != null) {
            notePlayer(online);
            return Optional.of(new Target(online.getUniqueId(), online.getName()));
        }

        try {
            UUID uuid = UUID.fromString(token);
            try (Connection connection = platform.storage().connection();
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT last_name FROM gm_player_directory WHERE player_uuid = ? LIMIT 1")) {
                statement.setString(1, uuid.toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (result.next()) {
                        return Optional.of(new Target(uuid, result.getString("last_name")));
                    }
                }
            }
            OfflinePlayer cached = Bukkit.getOfflinePlayer(uuid);
            if (cached.getName() != null) {
                return Optional.of(new Target(uuid, cached.getName()));
            }
            return Optional.empty();
        } catch (IllegalArgumentException ignored) {
        }

        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT player_uuid, last_name FROM gm_player_directory "
                             + "WHERE LOWER(last_name) = LOWER(?) ORDER BY last_seen_at DESC LIMIT 1")) {
            statement.setString(1, token);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    return Optional.of(new Target(
                            UUID.fromString(result.getString("player_uuid")),
                            result.getString("last_name")
                    ));
                }
            }
        }
        return Optional.empty();
    }

    public PunishmentRecord apply(
            ModerationActionType action,
            Target target,
            String reason,
            String sourceKind,
            String sourceId,
            Long durationMillis
    ) throws SQLException {
        if (action == null || target == null) {
            throw new IllegalArgumentException("Action and target are required.");
        }
        String cleanReason = clean(reason, 1800);
        if (cleanReason.isBlank()) {
            cleanReason = "No reason supplied.";
        }
        long now = System.currentTimeMillis();
        Long expiresAt = durationMillis == null ? null : now + Math.max(1_000L, durationMillis);
        UUID id = UUID.randomUUID();
        PunishmentRecord record = new PunishmentRecord(
                id, target.playerId(), target.playerName(), action, cleanReason,
                sourceKind == null ? "MINECRAFT" : sourceKind,
                clean(sourceId, 128), now, expiresAt, null, null
        );

        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO gm_punishments "
                             + "(punishment_uuid, player_uuid, player_name, action_type, reason, source_kind, source_id, "
                             + "created_at, expires_at, revoked_at, revoked_by) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL)")) {
            statement.setString(1, id.toString());
            statement.setString(2, target.playerId().toString());
            statement.setString(3, target.playerName());
            statement.setString(4, action.name());
            statement.setString(5, cleanReason);
            statement.setString(6, record.sourceKind());
            statement.setString(7, record.sourceId());
            statement.setLong(8, now);
            if (expiresAt == null) statement.setObject(9, null);
            else statement.setLong(9, expiresAt);
            statement.executeUpdate();
        }

        if (action == ModerationActionType.MUTE) {
            activeMutes.put(target.playerId(), record);
        } else if (action == ModerationActionType.BAN || action == ModerationActionType.TEMPBAN) {
            activeBans.put(target.playerId(), record);
        }

        applyImmediateEffect(record);
        publishApplied(record);
        return record;
    }

    public int revokeMute(Target target, String revokedBy, String reason) throws SQLException {
        int changed = revoke(target.playerId(), List.of(ModerationActionType.MUTE), revokedBy);
        activeMutes.remove(target.playerId());
        if (changed > 0) publishRevoked(target, "MUTE", revokedBy, reason);
        return changed;
    }

    public int revokeBan(Target target, String revokedBy, String reason) throws SQLException {
        int changed = revoke(target.playerId(),
                List.of(ModerationActionType.BAN, ModerationActionType.TEMPBAN), revokedBy);
        activeBans.remove(target.playerId());
        if (changed > 0) publishRevoked(target, "BAN", revokedBy, reason);
        return changed;
    }

    private int revoke(UUID playerId, List<ModerationActionType> actions, String revokedBy) throws SQLException {
        String placeholders = String.join(",", java.util.Collections.nCopies(actions.size(), "?"));
        String sql = "UPDATE gm_punishments SET revoked_at = ?, revoked_by = ? "
                + "WHERE player_uuid = ? AND revoked_at IS NULL "
                + "AND (expires_at IS NULL OR expires_at > ?) "
                + "AND action_type IN (" + placeholders + ")";
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            int index = 1;
            long now = System.currentTimeMillis();
            statement.setLong(index++, now);
            statement.setString(index++, clean(revokedBy, 128));
            statement.setString(index++, playerId.toString());
            statement.setLong(index++, now);
            for (ModerationActionType action : actions) {
                statement.setString(index++, action.name());
            }
            return statement.executeUpdate();
        }
    }

    public Optional<PunishmentRecord> activeMute(UUID playerId) {
        PunishmentRecord record = activeMutes.get(playerId);
        if (record != null && !record.active(System.currentTimeMillis())) {
            activeMutes.remove(playerId, record);
            return Optional.empty();
        }
        return Optional.ofNullable(record);
    }

    public Optional<PunishmentRecord> activeBan(UUID playerId) {
        PunishmentRecord record = activeBans.get(playerId);
        if (record != null && !record.active(System.currentTimeMillis())) {
            activeBans.remove(playerId, record);
            return Optional.empty();
        }
        return Optional.ofNullable(record);
    }

    public List<PunishmentRecord> history(UUID playerId, int limit) throws SQLException {
        int safe = Math.max(1, Math.min(limit, 50));
        List<PunishmentRecord> records = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT * FROM gm_punishments WHERE player_uuid = ? "
                             + "ORDER BY created_at DESC LIMIT " + safe)) {
            statement.setString(1, playerId.toString());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    records.add(read(result));
                }
            }
        }
        return List.copyOf(records);
    }

    public List<String> knownPlayerNames(String prefix, int limit) throws SQLException {
        String like = (prefix == null ? "" : prefix.toLowerCase(Locale.ROOT)) + "%";
        List<String> names = new ArrayList<>();
        try (Connection connection = platform.storage().connection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT last_name FROM gm_player_directory WHERE LOWER(last_name) LIKE ? "
                             + "ORDER BY last_seen_at DESC LIMIT " + Math.max(1, Math.min(limit, 50)))) {
            statement.setString(1, like);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) names.add(result.getString("last_name"));
            }
        }
        return names.stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private void applyImmediateEffect(PunishmentRecord record) {
        Player player = Bukkit.getPlayer(record.playerId());
        if (player == null) {
            return;
        }

        Component reason = Component.text(record.reason(), NamedTextColor.WHITE);
        switch (record.action()) {
            case WARN -> player.sendMessage(Component.text("[Server] Warning: ", NamedTextColor.GRAY).append(reason));
            case MUTE -> player.sendMessage(Component.text("[Server] You have been muted: ", NamedTextColor.GRAY).append(reason));
            case KICK -> player.kick(Component.text("Kicked: ", NamedTextColor.GRAY).append(reason));
            case TEMPBAN, BAN -> player.kick(Component.text("Banned: ", NamedTextColor.GRAY).append(reason));
        }
    }

    private PunishmentRecord read(ResultSet result) throws SQLException {
        Object expires = result.getObject("expires_at");
        Object revoked = result.getObject("revoked_at");
        return new PunishmentRecord(
                UUID.fromString(result.getString("punishment_uuid")),
                UUID.fromString(result.getString("player_uuid")),
                result.getString("player_name"),
                ModerationActionType.valueOf(result.getString("action_type")),
                result.getString("reason"),
                result.getString("source_kind"),
                result.getString("source_id"),
                result.getLong("created_at"),
                expires == null ? null : result.getLong("expires_at"),
                revoked == null ? null : result.getLong("revoked_at"),
                result.getString("revoked_by")
        );
    }

    private void publishApplied(PunishmentRecord record) {
        try {
            platform.integrations().publish(
                    IntegrationEventType.MODERATION_ACTION_APPLIED,
                    "player",
                    record.playerId().toString(),
                    "{\"playerUuid\":\"" + record.playerId() + "\""
                            + ",\"playerName\":\"" + json(record.playerName()) + "\""
                            + ",\"action\":\"" + record.action().name() + "\""
                            + ",\"reason\":\"" + json(record.reason()) + "\""
                            + ",\"sourceKind\":\"" + json(record.sourceKind()) + "\""
                            + ",\"sourceId\":\"" + json(record.sourceId()) + "\""
                            + ",\"expiresAt\":" + (record.expiresAt() == null ? "null" : record.expiresAt())
                            + "}"
            );
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not queue moderation alert for Iris: " + exception.getMessage());
        }
    }

    private void publishRevoked(Target target, String action, String revokedBy, String reason) {
        try {
            platform.integrations().publish(
                    IntegrationEventType.MODERATION_ACTION_REVOKED,
                    "player",
                    target.playerId().toString(),
                    "{\"playerUuid\":\"" + target.playerId() + "\""
                            + ",\"playerName\":\"" + json(target.playerName()) + "\""
                            + ",\"action\":\"" + json(action) + "\""
                            + ",\"revokedBy\":\"" + json(revokedBy) + "\""
                            + ",\"reason\":\"" + json(reason) + "\"}"
            );
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not queue moderation revoke alert for Iris: " + exception.getMessage());
        }
    }

    public void publishRemoteFailure(String commandId, String message) {
        try {
            platform.integrations().publish(
                    IntegrationEventType.MODERATION_COMMAND_FAILED,
                    "integration-command",
                    commandId,
                    "{\"message\":\"" + json(message) + "\"}"
            );
        } catch (SQLException exception) {
            plugin.getLogger().warning("Could not queue moderation command failure: " + exception.getMessage());
        }
    }

    private String clean(String value, int max) {
        if (value == null) return "";
        String clean = value.trim().replaceAll("\\s+", " ");
        return clean.length() <= max ? clean : clean.substring(0, max);
    }

    private String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    public record Target(UUID playerId, String playerName) {}
}
