# magefile-jetbrains-plugin

A GoLand plugin that integrates [Mage](https://magefile.org) build targets directly into the IDE. Run and debug Mage targets from the editor gutter or the Run/Debug Configurations window — no terminal required.

## Features

- **Gutter icons** — a run arrow appears next to every valid Mage target in the source editor. Click it to run or debug the target immediately.
- **Run configurations** — Mage targets appear as first-class run configurations under the **Mage** category in Run/Debug Configurations.
- **Debug support** — the debug action compiles the magefiles to a standalone binary with debug symbols, launches [Delve](https://github.com/go-delve/delve) in headless mode, and connects GoLand's Go debugger automatically. Breakpoints, variable inspection, and call stacks all work against the original `.go` magefiles.
- **Namespace targets** — methods on types declared as `mg.Namespace` are recognized as namespaced targets (e.g. `Build.Deploy` → `mage build:deploy`).

## Requirements

- GoLand 2024.1 or later
- [`mage`](https://magefile.org/#installation) on your `PATH` (or configured per-configuration)
- [`dlv`](https://github.com/go-delve/delve) on your `PATH` for debug support — install with:
  ```
  go install github.com/go-delve/delve/cmd/dlv@latest
  ```

## What counts as a Mage target?

The plugin recognizes a function as a Mage target when all of these are true:

1. The file contains `//go:build mage` or `// +build mage`
2. The function is exported (starts with an uppercase letter)
3. The return type is either nothing or `error`

Methods on a receiver type declared as `type <Name> mg.Namespace` are also recognized and produce targets of the form `<name>:<method>`.

## Usage

### Gutter icons

Open any `.go` file with `//go:build mage`. A green run arrow appears in the gutter next to each valid target function. Hovering shows a tooltip with the resolved target name. Clicking reveals two actions:

- **Run Mage: \<target\>** — runs the target via `mage <target>`
- **Debug Mage: \<target\>** — compiles and debugs the target via Delve

### Run configurations

Open **Run > Edit Configurations**, click **+**, and select **Mage**.

| Field | Description |
|---|---|
| Mage executable | Path to the `mage` binary. Leave empty to use `mage` from `PATH`. |
| Magefiles directory | Directory containing the `//go:build mage` files. Mage discovers all matching files in its working directory. |
| Target | Target name as passed on the CLI, e.g. `build` or `ns:deploy`. |
| CLI arguments | Extra arguments appended after the target. |
| Working directory | Override the process working directory. Defaults to the magefiles directory. |
| Environment variables | Extra environment variables merged with (or replacing) the parent environment. |
| Debug binary path (optional) | Where to write the compiled debug binary. Leave empty to use the system temp directory. |

## How debug mode works

1. **Compile** — `mage -keep -compile <binary>` is run with `GOFLAGS=-gcflags=main=-N` and `MAGEFILE_CACHE` pointed at the magefiles directory. This produces a standalone Go binary with debug symbols and preserves `mage_output_file.go` alongside the source files so the debugger can resolve the generated bootstrap code.
2. **VFS refresh** — GoLand's virtual file system is flushed synchronously so the IDE registers `mage_output_file.go` before the debugger connects.
3. **Start Delve** — `dlv exec <binary> --headless --api-version=2 --listen=127.0.0.1:<port> -- <target>` is launched on a randomly assigned free port.
4. **Connect debugger** — GoLand's Go Remote configuration type is located at runtime, configured with the Delve host/port via reflection, and executed with the debug executor. The temporary config is not saved to the Run Manager, so no extra entry appears in the configurations tree.
5. **Cleanup** — after the Delve process exits, the compiled binary and `mage_output_file.go` are deleted.

## Building

Requires JDK 17. Uses the [gradle-intellij-plugin](https://github.com/JetBrains/gradle-intellij-plugin).

```bash
# Compile
JAVA_HOME=/path/to/jdk-17 ./gradlew compileKotlin

# Build the plugin zip
JAVA_HOME=/path/to/jdk-17 ./gradlew buildPlugin

# Run a sandboxed GoLand instance with the plugin installed
JAVA_HOME=/path/to/jdk-17 ./gradlew runIde
```

The built zip is written to `build/distributions/`.

## Publishing

Set the `ORG_GRADLE_PROJECT_intellijPublishToken` environment variable to a JetBrains Marketplace token, then run:

```bash
./gradlew publishPlugin
```

## Project structure

```
src/main/kotlin/io/tylermmorton/magefile/
  MageLineMarkerProvider.kt       # Gutter run/debug icons for Mage targets
  MageRunConfiguration.kt         # Run configuration model + shared helpers
  MageRunConfigurationEditor.kt   # Configuration UI form
  MageRunConfigurationType.kt     # Registers the "Mage" configuration type
  MageConfigurationFactory.kt     # Factory that creates MageRunConfiguration instances
  MageDebugProgramRunner.kt       # Debug orchestration (compile → dlv → Go Remote)
  MageExecutableFileChooserDescriptor.kt  # File browser filter for the mage binary field
  MagePluginIcons.kt              # Loads the custom plugin icon

src/main/resources/
  META-INF/plugin.xml             # Plugin descriptor
  META-INF/pluginIcon.svg         # Marketplace / plugin settings icon (40×40)
  icons/gary.svg                  # Run configuration icon (16×16)
```