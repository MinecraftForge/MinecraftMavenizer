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

MinecraftMaven solves a long-standing pain point that we've attempted to solve with ForgeGradle in the past: the ability to have a static maven repository for Minecraft artifacts. This tool is designed to be a standalone tool, without the need of a Gradle plugin, to do so.

For the case of Minecraft Forge, this tool will be integrated (either as a library or via JavaVersion) by ForgeGradle 7, however the skeleton is set so that even Maven projects can benefit from the Forge/MCP toolchain.

## Usage

MinecraftMaven is a standalone Java tool that can be invoked through the command line. Here is an example:

```shell
java -jar minecraft-maven-0.1.0.jar --version 1.21.3-53.0.25
```

For now, MinecraftMaven only supports generating Forge repos (along with its related artifacts such as Client Extra, of course). If you want to read more on how to invoke this tool, read the main method at `net.minecraftforge.mcmaven.Main`.

## Preliminary Implementation

As MinecraftMaven continues to be developed, preliminary imimplementations will be available here depending on what is currently available. Until the release of 1.0.0, this is a good way to test the tool.

```groovy
import org.gradle.internal.os.OperatingSystem

// ...

repositories {
    maven {
        url = file('/home/jonathing/Development/git/minecraft/minecraft-forge/minecraft-maven/run/output/')
    }

    maven {
        url = uri('https://libraries.minecraft.net')
    }

    mavenCentral()

    maven {
        url = uri('https://maven.minecraftforge.net')
    }
}

def forgeOS = Attribute.of("net.minecraftforge.native.operatingSystem", OperatingSystemFamily)
def forgeMappingsChannel = Attribute.of("net.minecraftforge.mappings.channel", String)
def forgeMappingsVersion = Attribute.of("net.minecraftforge.mappings.version", String)

configurations.all {
    attributes {
        attribute(forgeOS, objects.named(OperatingSystemFamily, OperatingSystem.current().familyName))
        attribute(forgeMappingsChannel, 'official')
        attribute(forgeMappingsVersion, '1.21.3')
    }
}

dependencies {
    implementation('net.minecraftforge:forge:1.21.3-53.0.25')

    implementation('net.sf.jopt-simple:jopt-simple:5.0.4') { version { strictly '5.0.4' } }
}
```
