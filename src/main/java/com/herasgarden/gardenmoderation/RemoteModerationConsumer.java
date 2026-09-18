package com.herasgarden.gardenmoderation;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardencore.api.integration.IntegrationCommand;
import com.herasgarden.gardencore.api.integration.IntegrationCommandType;
import com.herasgarden.gardenmoderation.model.ModerationActionType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.sql.SQLException;
import java.util.EnumSet;
import java.util.Optional;

public final class RemoteModerationConsumer {
    private static final EnumSet<IntegrationCommandType> TYPES = EnumSet.of(
            IntegrationCommandType.MODERATION_WARN,
            IntegrationCommandType.MODERATION_MUTE,
            IntegrationCommandType.MODERATION_UNMUTE,
            IntegrationCommandType.MODERATION_KICK,
            IntegrationCommandType.MODERATION_TEMPBAN,
            IntegrationCommandType.MODERATION_BAN,
            IntegrationCommandType.MODERATION_UNBAN
    );

    private final JavaPlugin plugin;
    private final GardenPlatform platform;
    private final ModerationService moderation;
    private BukkitTask task;

    public RemoteModerationConsumer(JavaPlugin plugin, GardenPlatform platform, ModerationService moderation) {
        this.plugin = plugin;
        this.platform = platform;
        this.moderation = moderation;
    }

    public void start(long pollTicks, long staleMillis) {
        long interval = Math.max(20L, pollTicks);
        task = plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            try {
                for (IntegrationCommand command : platform.commands().claimPending(TYPES, 20, staleMillis)) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> execute(command));
                }
            } catch (SQLException exception) {
                plugin.getLogger().warning("Could not poll Iris moderation commands: " + exception.getMessage());
            }
        }, interval, interval);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    private void execute(IntegrationCommand command) {
        try {
            Optional<ModerationService.Target> targetOpt = moderation.resolveTarget(command.aggregateId());
            if (targetOpt.isEmpty()) {
                throw new IllegalArgumentException("Target is not in the Garden player directory.");
            }
            ModerationService.Target target = targetOpt.get();
            String reason = stringField(command.payload(), "reason").orElse("Moderation action from Iris.");
            String actor = stringField(command.payload(), "moderatorTag")
                    .or(() -> stringField(command.payload(), "moderatorId"))
                    .orElse("Iris");
            Long durationSeconds = longField(command.payload(), "durationSeconds").orElse(null);
            Long durationMillis = durationSeconds == null ? null : Math.multiplyExact(durationSeconds, 1000L);

            switch (command.type()) {
                case MODERATION_WARN ->
                        moderation.apply(ModerationActionType.WARN, target, reason, "DISCORD", actor, null);
                case MODERATION_MUTE ->
                        moderation.apply(ModerationActionType.MUTE, target, reason, "DISCORD", actor, durationMillis);
                case MODERATION_UNMUTE ->
                        moderation.revokeMute(target, actor, reason);
                case MODERATION_KICK ->
                        moderation.apply(ModerationActionType.KICK, target, reason, "DISCORD", actor, null);
                case MODERATION_TEMPBAN -> {
                    if (durationMillis == null || durationMillis <= 0) {
                        throw new IllegalArgumentException("Temporary ban requires durationSeconds.");
                    }
                    moderation.apply(ModerationActionType.TEMPBAN, target, reason, "DISCORD", actor, durationMillis);
                }
                case MODERATION_BAN ->
                        moderation.apply(ModerationActionType.BAN, target, reason, "DISCORD", actor, null);
                case MODERATION_UNBAN ->
                        moderation.revokeBan(target, actor, reason);
            }

            platform.commands().markProcessed(command.id());
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            plugin.getLogger().warning("Iris moderation command " + command.id() + " failed: " + message);
            moderation.publishRemoteFailure(command.id().toString(), message);
            try {
                platform.commands().release(command.id(), message);
            } catch (SQLException releaseFailure) {
                plugin.getLogger().warning("Could not release failed Iris command " + command.id()
                        + ": " + releaseFailure.getMessage());
            }
        }
    }

    private Optional<String> stringField(String json, String key) {
        if (json == null) return Optional.empty();
        int keyAt = json.indexOf("\"" + key + "\"");
        if (keyAt < 0) return Optional.empty();
        int colon = json.indexOf(':', keyAt + key.length() + 2);
        if (colon < 0) return Optional.empty();
        int index = colon + 1;
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) index++;
        if (index >= json.length() || json.charAt(index) != '"') return Optional.empty();
        index++;

        StringBuilder value = new StringBuilder();
        boolean escape = false;
        for (; index < json.length(); index++) {
            char ch = json.charAt(index);
            if (escape) {
                escape = false;
                switch (ch) {
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case '\\' -> value.append('\\');
                    case '"' -> value.append('"');
                    default -> value.append(ch);
                }
                continue;
            }
            if (ch == '\\') {
                escape = true;
                continue;
            }
            if (ch == '"') {
                return Optional.of(value.toString());
            }
            value.append(ch);
        }
        return Optional.empty();
    }

    private Optional<Long> longField(String json, String key) {
        if (json == null) return Optional.empty();
        int keyAt = json.indexOf("\"" + key + "\"");
        if (keyAt < 0) return Optional.empty();
        int colon = json.indexOf(':', keyAt + key.length() + 2);
        if (colon < 0) return Optional.empty();
        int index = colon + 1;
        while (index < json.length() && Character.isWhitespace(json.charAt(index))) index++;

        int start = index;
        if (index < json.length() && json.charAt(index) == '-') index++;
        while (index < json.length() && Character.isDigit(json.charAt(index))) index++;
        if (index == start || (index == start + 1 && json.charAt(start) == '-')) return Optional.empty();

        try {
            return Optional.of(Long.parseLong(json.substring(start, index)));
        } catch (NumberFormatException exception) {
            return Optional.empty();
        }
    }

    private String unescape(String value) {
        StringBuilder out = new StringBuilder();
        boolean escape = false;
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (!escape) {
                if (ch == '\\') escape = true;
                else out.append(ch);
                continue;
            }
            escape = false;
            switch (ch) {
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case '\\' -> out.append('\\');
                case '"' -> out.append('"');
                default -> out.append(ch);
            }
        }
        if (escape) out.append('\\');
        return out.toString();
    }
}
