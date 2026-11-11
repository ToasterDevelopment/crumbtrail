# Crumbtrail

**Stop screenshotting your coords.** Save a spot with one command, then get an on-screen arrow that leads you back to it.

Found some cows? A good village? Your stuff after a bad fall into a ravine? Type `/crumb save cows` and forget about it. When you want to go back, `/crumb go cows` puts a small arrow and distance at the top of your screen that turns as you turn, until you arrive.

## Features

- **Save any spot** with a name, like `/crumb save cows by the river`
- **On-screen arrow** pointing the way back, with distance and how far up or down it is
- **Death points saved automatically.** Click *[Show the way back]* in chat after you respawn.
- **Clickable list:** `/crumb` shows everything you've saved. Click a spot to follow it, or click *[copy]* to copy the coords.
- **Tab-completes names.** No numbers to remember.
- **Separate lists for each world and server**
- **Client-side only:** works on any server, including vanilla. Nothing to install on the server.
- **Lightweight:** no config screens or keybinds, and nothing extra beyond Fabric API.

## Commands

| Command | What it does |
|---|---|
| `/crumb` | List your saved spots |
| `/crumb save <name>` | Save where you're standing |
| `/crumb go <name>` | Show the arrow to a spot |
| `/crumb stop` | Hide the arrow |
| `/crumb delete <name>` | Remove a spot |

Your last 5 deaths are kept as `death-1` (most recent) through `death-5`, so `/crumb go death-1` takes you back to where you just died.

## Notes

- Requires [Fabric API](https://modrinth.com/mod/fabric-api).
- Saved spots are stored on your computer in `.minecraft/config/crumbtrail/`, one file per world or server.
- Some servers have rules about waypoint or minimap mods. Check before using it on one.
