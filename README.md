
# Minecraft Mavenizer

A pure-blooded Java tool to generate a maven repository for Minecraft artifacts.

## Preamble

Due to Minecraft being closed source, and (previously) obfusicated it can not be used directly as a library in the form that Mojang releases it.
This is designed to be a stand alone tool that will generate a directory that is suitable to be used as a maven respository containing Minecraft related artifacts.
The basic conept is to replace the hacks into Gradle's internals that our ForgeGradle tool was reqired to do. In doing so disconnects the core functionality from Gradle, 
allowing it to be more stable considering how much Gradle likes to change its 'best practices'.

This tool is designed as a executable jar, **not** a library that can be referenced by other java code. As such the only 'public api' is the command line arguments.
As such anything that can call a executable jar, should be able to use this. Opening the possibility for usage in other build systems such as maven. This is not tested, and may need additional changes. If people want to use this tool in other build systems. Please feel encouraged to file an issue or pull request.

#### Broad Features
  - Functional:
    - MCPConfig based artifacts: Vanilla Minecraft (1.12.2+)
    - UserDev3+ Forge libraries, these are versions of Forge that use ForgeGradle 3+ (Minecraft 1.13+, and some builds of 1.12.2)
    - Parchment and Official mappings.
  - TODO:
    - Legacy Forge versions.
       - Forge Gradle has gone through many eras, and before that we had a python based build system. It will take some time but the plan is to eventually support every version of Forge that has been released.
     - Support Artifact Transformers allowing ModLauncher/Mixin/Whatever to apply static transformation of artifacts? Probably not needed.
     - Deobfuscating arbitrary dependencies. Basically a replacement for `fg.deobf` allowing mods to be used in different mappings then they are released.
     - Better cacheing management, and debug information on cache misses.

## Usage

Minecraft Mavenizer is a standalone Java tool that can be invoked through the command line. Here is an example:

```shell
java -jar minecraft-maven.jar --version 1.21.3-53.0.25
```

As Forge is the main target of this tool, that is all you need to generate the Forge artifacts. 

> [!WARNING]
> **There is no public API for this tool!** This is designed to solely be a CLI tool, which means that all of the implementations are internal. We reserve the right to change the internal implementation at any time.

Mavenizer is seperated into multiple `tasks` specified by the `--maven`, `--mcp`, and `--mcp_data` command line arguments.
The only task consumers should care about is the `--maven` task, and thus is the default if those arguments are omitted. And the only one I'm going to document here. The others are used in ForgeDev, our plugin for making Forge itself.

## Maven task
|      Argument         |           Default          | Description
| --------------------- | -------------------------- | ------------
| --version `String`    |                            | The specific artifact version to generate. This is the only required argument.
| --artifact `String`   | `net.minecraftforge:forge` | The artifact to generate. |
| --client              |                            | Shorthand for `--artifact net.minecraft:client`
| --server              |                            | Shorthand for `--artifact net.minecraft:server`
| --forge               |                            | Shorthand for `--artifact net.minecraftforge:forge`
| --mapping-data        |                            | Shorthand for `--artifact net.minecraft:mappings`
| --mc                  |                            | Shorthand for `--artifact net.minecraft:joined`
| --mappings `String`   | `official`                 | Mappings to use for this artifact. Formatted as `channel:version`. If version is missing, will attempt use the detected `minecraft` version of the artifact.
| --parchment `version` |                            | Version of parchment mappings to use, snapshots are not supported. Shorthand for `--mappings parchment:version`
| --output `File`       | `./output`                 | Root directory to generate the maven repository.
| --cache `File`        | `./cache`                  | The directory to use for caching things used for building.
| --jdk-cache `File`    | `./cache/jdks`             | Directory to store jdks downloaded from the disco api.
| --cache-only          |                            | Only use caches, fail if any downloads need to occur or if a task needs to do work.
| --offline             |                            | Allows offline operations, fails if any downloads need to occur.
| --dependencies-only   |                            | Outputs the maven containing only the Gradle Module and POM for the artifact's dependencies without outputting the artifact itself
| --global-auxiliary-variants |                      | Declares sources and javadoc jars as global variants, no matter the mapping version. This is used to work around gradle/gradle#35065
| --repository `String` |                            |**EXPERIMENTAL**: URL of a foreign maven repository to use for dependencies. The format is `name,url`. The name must not include any commas.

Supported Artifacts:
  - `net.minecraftfroge:forge`
  - `net.minecraft:client`
  - `net.minecraft:client-extra`
  - `net.minecraft:server`
  - `net.minecraft:server-extra`
  - `net.minecraft:joined`
  - `net.minecraft:joined-extra`
  - `net.minecraft:mappings`

Supported Mapping Channels:
  - `official`: Official mappings provided by Mojang, located by the launcher's version.json file. Only available for versions Mojang has released the mappings for.
  - `notch`: Also known as obfusicated, the raw names of the class as Mojang released them. Note: Mojang is intending to remove obfusication, so for versions that don't have obf, `notch` will not be supported.
  - `srg` or `searge`: The intermediate names used by MCPConfig to make the code readable. One step above `notch`.
  - `parchment`: A layer over the `official` mappings that add parameter names, and javadoc. See https://parchmentmc.org/


## What artifacts are generated?
For all artifacts, the standard maven `pom` file is generated with appropriate dependencies. 
For artifacts that support mappings, we also generate a [Gradle Module File](https://docs.gradle.org/current/userguide/publishing_gradle_module_metadata.html) to specify the mapping specific variants.


### For `net.minecraft` `client`, `server`, and `joined`
  - `side-version.pom`: Maven metadata pom file containing all dependencies.
  - `side-version-metadata.zip`: A zip file containing metadata about this artifact.
      It currently only has 2 files in it. version.properties, which contained `version=1` to know what format this zip is in. And `version.json` which is the version.json file from the Minecraft launcher.  
   - `side-version.jar`: The main archive for this artifact. This supports mappings, as such specific versions are referenced in the module file.
   - `side-version-sources.jar`: The sources for this artifact. Not available for `notch`, `srg`, `searge` mappings. This supports mappings, as such specific versions are referenced in the module file.

### For `net.minecraft` `client-extra`, `server-extra`
  - `side-version.jar`: This is a legacy file that houses all the `extra` data in the vanilla Minecraft jars. The server typically doesn't have anything in it anymore. But it used to have all the dependency libraries when Mojang released the Server jar as a normal `uberjar`. The client contains all the non-class file entries. Such as images, sounds, language files that may be in the Client vanilla jar file.

   
 ### For `net.minecraft:mappings`
   - `mappings-version.jar`: A archive containing csv files mapping SRG intermediate names to the specified mapping. This is mainly intended for old versions which use SRG at runtime, and need to runtime remap reflection to mapped names.
 
 ### For `net.minecraftforge:forge`:
 Currently only UserDev3 files are supported.
 Generating this artifact will automatically generate the appropriate `net.minecraft:client-extra`, and `net.minecraft:mappings` artifacts.
   - `forge-version.jar`: Main artifact, contains classes and a data for the game. This supports mappings, as such specific versions are referenced in the module file.
   - `forge-version-sources.jar`: The sources for this artifact. This supports mappings, as such specific versions are referenced in the module file.
   - `forge-version-metadata.zip`: A metadata zip containing information useful for ForgeGradle. Currently contains:
     -  `version.properties`: A text file containing `version=1`, This will allow us to change the format of files in this archive if needed.
     - `runs.json`: Run configuration information from Userdev's config. Useful for SlimeLauncher to actually launch the game.
     - `version.json`: The version.json file from the Minecraft launcher.