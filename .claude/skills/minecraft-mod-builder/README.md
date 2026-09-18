# minecraft-mod-builder

A Claude Code skill that walks a Java developer through building a Minecraft mod from scratch — supports Fabric and NeoForge across multiple Minecraft versions.

## Install

Clone into your Claude Code skills directory:

```bash
git clone https://github.com/Riloox/minecraft-mod-builder.git ~/.claude/skills/minecraft-mod-builder
```

On Windows (PowerShell):

```powershell
git clone https://github.com/Riloox/minecraft-mod-builder.git "$env:USERPROFILE\.claude\skills\minecraft-mod-builder"
```

## Use

In a Claude Code session, just ask for a mod:

> "Help me make a Minecraft mod"

Claude will invoke the skill, ask for your loader (Fabric/NeoForge), MC version, and feature scope, then scaffold the project, launch `gradlew runClient`, and iterate on features with you.

## Requirements

- [Claude Code](https://claude.com/claude-code)
- A JDK matching your target Minecraft version (the skill tells you which)
- Git
