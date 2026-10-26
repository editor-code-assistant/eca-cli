# ECA CLI

[![Release](https://img.shields.io/github/v/release/editor-code-assistant/eca-cli)](https://github.com/editor-code-assistant/eca-cli/releases)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

ECA (Editor Code Assistant) CLI is an AI-powered pair-programming client for your terminal, no editor required.
It connects to an external `eca` server process to provide interactive chat, tool calls with live diffs, context management and more, all keyboard-driven.

For more details about ECA, features and configuration, check [ECA server](https://github.com/editor-code-assistant/eca).

This CLI will auto download `eca` and manage the process.

## Requirements

- Linux or macOS, on x86_64 or arm64

## Installation

```bash
bash <(curl -fsSL https://raw.githubusercontent.com/editor-code-assistant/eca-cli/main/install)
```

This installs a single self-contained `eca-cli` binary, no Babashka or JVM needed, in `~/.local/bin` (`/usr/local/bin` as root); pass `--dir <dir>` to pick another place. Run it again to update.

Binaries are also on the [releases page](https://github.com/editor-code-assistant/eca-cli/releases), along with `eca-cli.jar`, which runs on any other platform with [Babashka](https://babashka.org/): `bb eca-cli.jar`.

## Quickstart

```bash
cd your-project
eca-cli
```

## Features

- Chat with any model and agent configured in ECA, with streaming responses
- Approve tool calls inline, with coloured diffs for file edits
- Add files as context with `@`
- Resume previous chats
- MCP servers and background jobs at a glance
- Collapsible tool calls, thinking and sub-agent blocks

## Usage

```
eca-cli [--workspace <path>] [--model <model>] [--agent <agent>] [--trust] [--eca <path>]
```

Run `eca-cli --help` for details.

### Commands

Type `/` in an empty input to pick one.

| Command | Action |
|---|---|
| `/model` | Pick a model (also `Ctrl+L`) |
| `/agent` | Pick an agent |
| `/new` | Start a fresh chat |
| `/chats` | Browse and resume previous chats |
| `/mcp` | MCP servers status |
| `/jobs` | Background shell jobs |
| `/clear` | Clear the chat display |
| `/login` | Log in to a provider |
| `/help` | Show available commands |
| `/quit` | Exit |

### Keybindings

| Key | Action |
|---|---|
| `Enter` | Send message / toggle the focused block |
| `y` / `n` / `Y` | Approve / reject / always approve a tool call |
| `Esc` | Stop the running prompt, close a picker, clear focus |
| `Tab` / `Shift+Tab` | Cycle focus through chat blocks |
| `Alt+↑` / `Alt+↓` | Jump between top-level blocks |
| `Alt+c` / `Alt+o` | Collapse / expand all blocks |
| `↑` / `↓` | Input history (or move focus) |
| `PgUp` / `PgDn`, mouse wheel | Scroll |
| `Ctrl+C` | Quit |

## Troubleshooting

Check [troubleshooting](https://eca.dev/troubleshooting) docs section.

## Development

Requires [Babashka](https://babashka.org/) 1.12.215 or later.

```bash
bb run     # run from source, takes the same options as eca-cli
bb test    # unit tests
bb nrepl   # nREPL on port 7888
bb dist    # build the release binaries into dist/
```

Every push to `main` is released automatically. See [CLAUDE.md](./CLAUDE.md) for the architecture.

## Contributing 💙

Contributions are very welcome, please open an issue for discussion or a pull request.

## License

Apache License 2.0, see [LICENSE](./LICENSE).
