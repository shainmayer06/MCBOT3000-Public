# MatthauBot 3000

MatthauBot 3000 is an open-source, server-side Fabric mod that bridges Minecraft chat with one configured Discord channel and sends configurable player login and logout notifications.

## Features

- Minecraft chat to Discord
- Discord chat to Minecraft
- Configurable login and logout message templates
- Optional random template selection and repeat avoidance
- Server-side bot token configuration

This Minecraft 26.2 release does not include Chronicle, event-history recording, or server start/stop announcements.

## Requirements

- Minecraft Java Edition 26.2
- Fabric Loader 0.19.3 or newer, compatible with Minecraft 26.2
- Fabric API 0.154.2 or newer, compatible with Minecraft 26.2
- Java 25 or newer

Install the mod JAR on the server in its mods directory. Players do not need to install it on their clients.

## Discord Setup

1. Create an application and bot in the [Discord Developer Portal](https://discord.com/developers/applications).
2. Enable Message Content Intent in the bot settings.
3. Invite the bot to your Discord server.
4. Give it View Channel and Send Messages permissions in the target channel.
5. Copy the bot token and target channel ID.

Treat the bot token like a password. Never publish it or commit it to a repository. If exposed, reset it in the Discord Developer Portal.

## Installation

1. Stop your Minecraft server.
2. Put the mod JAR in the server's mods directory.
3. Start the server once to generate the configuration file.
4. Stop the server and open config/matthau-bot-3000.json.
5. Set botToken to your Discord bot token and channelId to your target Discord channel ID.
6. Save the file and restart the server.

## Configuration and Message Templates

The configuration contains these settings:

- botToken: Your Discord bot token.
- channelId: The Discord channel used for chat and notifications.
- joinMessages: Login notification templates.
- leaveMessages: Logout notification templates.
- randomJoinMessages: When enabled, randomly selects a login template.
- randomLeaveMessages: When enabled, randomly selects a logout template.
- avoidImmediateRepeat: When enabled, avoids immediately repeating the same randomly selected template when possible.

Customize the joinMessages and leaveMessages arrays to change the wording, add templates, or remove templates. Use %player% wherever the player's name should appear.

Example login templates:

    "joinMessages": [
      "Welcome back, %player%!",
      "%player% just joined the adventure."
    ]

To disable logout notifications, use an empty array:

    "leaveMessages": []

These examples show individual configuration entries, not a complete configuration file. When editing your configuration, preserve the surrounding JSON structure and keep the JSON syntax valid.

Back up the configuration and stop the server before editing it. Restart the server after saving your changes.

## Build from Source

Requires Java 25 or newer.

From the project directory, run:

    ./gradlew build

On Windows, run:

    gradlew.bat build

Build artifacts are placed in build/libs/. Use the regular mod JAR to install the mod, not the sources JAR.

## Troubleshooting

- Check the server log if the Discord bridge does not start.
- Verify that the bot token and channel ID are correct.
- Confirm that Message Content Intent is enabled in the Discord Developer Portal.
- Confirm that the bot has access to the target channel and the required permissions.
- Back up and validate the JSON configuration if configuration errors occur.

## License

Licensed under the MIT License. See [LICENSE](LICENSE).