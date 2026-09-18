package com.herasgarden.gardenmoderation;

import com.herasgarden.gardencore.api.ui.GardenMessages;
import com.herasgarden.gardenmoderation.model.PunishmentRecord;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Optional;

public final class ModerationListener implements Listener {
    private final JavaPlugin plugin;
    private final ModerationService moderation;

    public ModerationListener(JavaPlugin plugin, ModerationService moderation) {
        this.plugin = plugin;
        this.moderation = moderation;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (event.getPlayer().hasPermission("gardenmoderation.bypass")) {
            return;
        }

        Optional<PunishmentRecord> mute = moderation.activeMute(event.getPlayer().getUniqueId());
        if (mute.isEmpty()) {
            return;
        }

        event.setCancelled(true);
        PunishmentRecord record = mute.get();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            String remaining = record.expiresAt() == null
                    ? "permanent"
                    : humanRemaining(record.expiresAt() - System.currentTimeMillis());
            event.getPlayer().sendMessage(GardenMessages.prefix()
                    .append(Component.text("You are muted (" + remaining + "): " + record.reason(),
                            NamedTextColor.WHITE)));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        Optional<PunishmentRecord> ban = moderation.activeBan(event.getUniqueId());
        if (ban.isEmpty()) {
            return;
        }

        PunishmentRecord record = ban.get();
        String remaining = record.expiresAt() == null
                ? "Permanent"
                : humanRemaining(record.expiresAt() - System.currentTimeMillis());
        event.disallow(
                AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                Component.text("Banned from The Garden SMP", NamedTextColor.GRAY)
                        .append(Component.newline())
                        .append(Component.text(record.reason(), NamedTextColor.WHITE))
                        .append(Component.newline())
                        .append(Component.text("Duration: " + remaining, NamedTextColor.GRAY))
        );
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        var uuid = player.getUniqueId();
        var name = player.getName();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                moderation.notePlayer(uuid, name);
            } catch (SQLException exception) {
                plugin.getLogger().warning("Could not update moderation player directory for "
                        + name + ": " + exception.getMessage());
            }
        });
    }

    private String humanRemaining(long millis) {
        if (millis <= 0) return "expired";
        Duration duration = Duration.ofMillis(millis);
        long days = duration.toDays();
        if (days > 0) return days + "d";
        long hours = duration.toHours();
        if (hours > 0) return hours + "h";
        long minutes = duration.toMinutes();
        if (minutes > 0) return minutes + "m";
        return Math.max(1L, duration.toSeconds()) + "s";
    }
}
