# How GardenModeration Works

GardenModeration stores moderation actions in the shared Garden database through GardenCore. Active punishments survive restart.

Iris can submit moderation commands through the Garden integration inbox. GardenModeration consumes those commands and applies the matching action in Minecraft.

Moderation history is durable so staff can review prior actions even after the target player leaves the server.