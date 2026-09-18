package com.herasgarden.gardenmoderation;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenmoderation.model.ModerationActionType;
import com.herasgarden.gardenmoderation.model.PunishmentRecord;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

public final class ModerationCommand implements CommandExecutor, TabCompleter {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final ModerationService moderation;
    private final int historyLimit;

    public ModerationCommand(ModerationService moderation, int historyLimit) {
        this.moderation = moderation;
        this.historyLimit = historyLimit;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        String permission = permission(name);
        if (!sender.hasPermission(permission)) {
            send(sender, "You do not have permission to do that.");
            return true;
        }

        try {
            return switch (name) {
                case "warn" -> apply(sender, ModerationActionType.WARN, args, false, false);
                case "mute" -> apply(sender, ModerationActionType.MUTE, args, true, true);
                case "kick" -> apply(sender, ModerationActionType.KICK, args, false, false);
                case "tempban" -> apply(sender, ModerationActionType.TEMPBAN, args, true, false);
                case "ban" -> apply(sender, ModerationActionType.BAN, args, false, false);
                case "unmute" -> revoke(sender, args, true);
                case "unban" -> revoke(sender, args, false);
                case "modhistory" -> history(sender, args);
                default -> false;
            };
        } catch (SQLException exception) {
            send(sender, "The moderation database could not be updated right now.");
            return true;
        }
    }

    private boolean apply(CommandSender sender, ModerationActionType type, String[] args,
                          boolean durationRequired, boolean allowPermanent) throws SQLException {
        int reasonStart = durationRequired ? 2 : 1;
        if (args.length <= reasonStart) {
            send(sender, usage(type));
            return true;
        }

        Optional<ModerationService.Target> targetOpt = moderation.resolveTarget(args[0]);
        if (targetOpt.isEmpty()) {
            send(sender, "That player is not in the Garden player directory yet.");
            return true;
        }
        ModerationService.Target target = targetOpt.get();

        Long duration = null;
        if (durationRequired) {
            duration = DurationParser.parseMillis(args[1], allowPermanent);
            if (duration != null && duration < 0) {
                send(sender, "Use a duration like 10m, 2h, 3d, or 1w"
                        + (allowPermanent ? ", or permanent." : "."));
                return true;
            }
            if (!allowPermanent && duration == null) {
                send(sender, "A temporary ban needs a duration.");
                return true;
            }
        }

        String reason = String.join(" ", Arrays.copyOfRange(args, reasonStart, args.length)).trim();
        PunishmentRecord record = moderation.apply(
                type, target, reason, "MINECRAFT", sender.getName(), duration);

        String durationText = record.expiresAt() == null
                ? (type == ModerationActionType.MUTE || type == ModerationActionType.BAN ? " permanently" : "")
                : " for " + DurationParser.describe(record.expiresAt() - record.createdAt());
        send(sender, type.name().toLowerCase(Locale.ROOT) + " applied to "
                + target.playerName() + durationText + ".");
        return true;
    }

    private boolean revoke(CommandSender sender, String[] args, boolean mute) throws SQLException {
        if (args.length < 1) {
            send(sender, mute ? "Use /unmute <player> [reason]." : "Use /unban <player> [reason].");
            return true;
        }
        Optional<ModerationService.Target> targetOpt = moderation.resolveTarget(args[0]);
        if (targetOpt.isEmpty()) {
            send(sender, "That player is not in the Garden player directory yet.");
            return true;
        }
        String reason = args.length > 1
                ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim()
                : "Removed by staff.";
        int changed = mute
                ? moderation.revokeMute(targetOpt.get(), sender.getName(), reason)
                : moderation.revokeBan(targetOpt.get(), sender.getName(), reason);
        send(sender, changed > 0
                ? (mute ? "Mute removed from " : "Ban removed from ") + targetOpt.get().playerName() + "."
                : "That player has no active " + (mute ? "mute." : "ban."));
        return true;
    }

    private boolean history(CommandSender sender, String[] args) throws SQLException {
        if (args.length < 1) {
            send(sender, "Use /modhistory <player>.");
            return true;
        }
        Optional<ModerationService.Target> targetOpt = moderation.resolveTarget(args[0]);
        if (targetOpt.isEmpty()) {
            send(sender, "That player is not in the Garden player directory yet.");
            return true;
        }
        List<PunishmentRecord> history = moderation.history(targetOpt.get().playerId(), historyLimit);
        sender.sendMessage(Component.text("[Server] Moderation history for " + targetOpt.get().playerName(),
                NamedTextColor.GRAY));
        if (history.isEmpty()) {
            sender.sendMessage(Component.text("No Garden moderation actions.", NamedTextColor.WHITE));
            return true;
        }
        long now = System.currentTimeMillis();
        for (PunishmentRecord record : history) {
            String state = record.revokedAt() != null ? "revoked"
                    : record.expiresAt() != null && record.expiresAt() <= now ? "expired"
                    : record.action() == ModerationActionType.KICK || record.action() == ModerationActionType.WARN
                    ? "recorded" : "active";
            sender.sendMessage(Component.text(
                    TIME.format(Instant.ofEpochMilli(record.createdAt())) + " | "
                            + record.action() + " | " + state + " | " + record.reason(),
                    NamedTextColor.WHITE));
        }
        return true;
    }

    private String permission(String command) {
        return switch (command) {
            case "warn" -> "gardenmoderation.warn";
            case "mute", "unmute" -> "gardenmoderation.mute";
            case "kick" -> "gardenmoderation.kick";
            case "ban", "unban", "tempban" -> "gardenmoderation.ban";
            case "modhistory" -> "gardenmoderation.history";
            default -> "gardenmoderation.history";
        };
    }

    private String usage(ModerationActionType type) {
        return switch (type) {
            case WARN -> "Use /warn <player> <reason>.";
            case MUTE -> "Use /mute <player> <duration|permanent> <reason>.";
            case KICK -> "Use /kick <player> <reason>.";
            case TEMPBAN -> "Use /tempban <player> <duration> <reason>.";
            case BAN -> "Use /ban <player> <reason>.";
        };
    }

    private void send(CommandSender sender, String message) {
        GardenMessages.send(sender, message);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        try {
            if (args.length == 1) {
                return moderation.knownPlayerNames(args[0], 25);
            }
        } catch (SQLException ignored) {
        }
        if ((command.getName().equalsIgnoreCase("mute") || command.getName().equalsIgnoreCase("tempban"))
                && args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            List<String> values = command.getName().equalsIgnoreCase("mute")
                    ? List.of("10m", "30m", "1h", "1d", "7d", "permanent")
                    : List.of("1h", "1d", "3d", "7d", "30d");
            return values.stream().filter(value -> value.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
