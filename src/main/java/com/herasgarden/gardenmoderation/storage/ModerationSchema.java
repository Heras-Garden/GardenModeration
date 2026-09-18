package com.herasgarden.gardenmoderation.storage;

import com.herasgarden.gardencore.api.storage.GardenStorage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public final class ModerationSchema {
    private ModerationSchema() {}

    public static void ensure(GardenStorage storage) throws SQLException {
        try (Connection connection = storage.connection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gm_player_directory ("
                    + "player_uuid VARCHAR(36) PRIMARY KEY,"
                    + "last_name VARCHAR(32) NOT NULL,"
                    + "last_seen_at BIGINT NOT NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gm_player_name "
                    + "ON gm_player_directory (last_name)");

            statement.executeUpdate("CREATE TABLE IF NOT EXISTS gm_punishments ("
                    + "punishment_uuid VARCHAR(36) PRIMARY KEY,"
                    + "player_uuid VARCHAR(36) NOT NULL,"
                    + "player_name VARCHAR(32) NOT NULL,"
                    + "action_type VARCHAR(24) NOT NULL,"
                    + "reason TEXT NOT NULL,"
                    + "source_kind VARCHAR(24) NOT NULL,"
                    + "source_id VARCHAR(128) NULL,"
                    + "created_at BIGINT NOT NULL,"
                    + "expires_at BIGINT NULL,"
                    + "revoked_at BIGINT NULL,"
                    + "revoked_by VARCHAR(128) NULL)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gm_punishments_player "
                    + "ON gm_punishments (player_uuid, created_at)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS idx_gm_punishments_active "
                    + "ON gm_punishments (player_uuid, action_type, revoked_at, expires_at)");
        }
    }
}
