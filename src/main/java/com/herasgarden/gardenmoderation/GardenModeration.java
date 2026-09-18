package com.herasgarden.gardenmoderation;

import com.herasgarden.gardencore.api.GardenPlatform;
import com.herasgarden.gardenmoderation.storage.ModerationSchema;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.SQLException;
import java.util.List;

public final class GardenModeration extends JavaPlugin {
    private GardenPlatform platform;
    private ModerationService moderation;
    private RemoteModerationConsumer remoteConsumer;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        RegisteredServiceProvider<GardenPlatform> registration =
                getServer().getServicesManager().getRegistration(GardenPlatform.class);
        if (registration == null || registration.getProvider() == null) {
            getLogger().severe("GardenCore platform service is unavailable.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        platform = registration.getProvider();

        try {
            ModerationSchema.ensure(platform.storage());
            moderation = new ModerationService(this, platform);
            moderation.refresh();

            for (var player : getServer().getOnlinePlayers()) {
                moderation.notePlayer(player);
            }
        } catch (SQLException | RuntimeException exception) {
            getLogger().severe("GardenModeration could not start: " + exception.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        ModerationCommand command = new ModerationCommand(
                moderation,
                getConfig().getInt("history.display-limit", 10)
        );
        for (String name : List.of(
                "warn", "mute", "unmute", "kick", "tempban", "ban", "unban", "modhistory")) {
            PluginCommand pluginCommand = getCommand(name);
            if (pluginCommand != null) {
                pluginCommand.setExecutor(command);
                pluginCommand.setTabCompleter(command);
            }
        }

        getServer().getPluginManager().registerEvents(
                new ModerationListener(this, moderation), this);

        remoteConsumer = new RemoteModerationConsumer(this, platform, moderation);
        long pollTicks = getConfig().getLong("iris.poll-ticks", 40L);
        long staleMillis = Math.max(30L, getConfig().getLong("iris.stale-command-seconds", 300L)) * 1000L;
        remoteConsumer.start(pollTicks, staleMillis);

        long refreshTicks = Math.max(200L, getConfig().getLong("cache-refresh-ticks", 1200L));
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                moderation.refresh();
            } catch (SQLException exception) {
                getLogger().warning("Could not refresh moderation cache: " + exception.getMessage());
            }
        }, refreshTicks, refreshTicks);

        getLogger().info("GardenModeration enabled. Persistent punishments and Iris moderation are active.");
    }

    @Override
    public void onDisable() {
        if (remoteConsumer != null) {
            remoteConsumer.stop();
        }
    }
}
