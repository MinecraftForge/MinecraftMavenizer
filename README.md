# Minecraft Mavenizer

A pure-blooded Java tool to generate a maven repository for Minecraft artifacts.

## Requirements (Delete on Release)

- Fully-fledged generation of MCPConfig-based artifacts and UserDev.
  - This also include the ability to use this tool to potentially replace clean in ForgeDev.
- Code cleanup (strictly API/Main, internal code does not need a mandated cleanup as long as it is maintainable).
- Everything must adhere to the cache system. There are a few steps (albeit cheap ones) that do not use the caching system such as POM and Gradle Module Metadata generation.
- The ability to use MCP mappings (Parchment support can come sometime later, after ForgeDev is able to benefit from new toolchain).
  - The groundwork for this using Gradle Module variants is already in place.
- Interface with ModLauncher and other transformer services that want to interact with Minecraft artifacts before Gradle artifact transformers.

## Purpose

Minecraft Mavenizer solves a long-standing pain point that we've attempted to solve with ForgeGradle in the past: the ability to have a static maven repository for Minecraft artifacts. This tool is designed to be a standalone tool, without the need of a Gradle plugin, to do so.

For the case of Minecraft Forge, this tool will be integrated (either as a library or via JavaVersion) by ForgeGradle 7, however the skeleton is set so that even Maven projects can benefit from the Forge/MCP toolchain.

## Usage

Minecraft Mavenizer is a standalone Java tool that can be invoked through the command line. Here is an example:

```shell
java -jar minecraft-maven-0.1.0.jar --version 1.21.3-53.0.25
```

> [!WARNING]
> **There is no public API for this tool!** This is designed to solely be a CLI tool, which means that all of the implementations are internal. We reserve the right to change the internal implementation at any time.
