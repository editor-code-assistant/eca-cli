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

- Chat with any model and agent configured in ECA, with streaming responses rendered as markdown: syntax-highlighted code, tables, lists and links
- Approve tool calls inline, with coloured diffs for file edits and a breakdown of shell commands; remember approvals for the chat
- Answer the agent's questions by picking an option or typing your own answer
- Steer the agent while it works, or queue your next message
- Follow the agent's plan in a task panel above the prompt
- The server's commands, skills and prompts from the `/` picker
- A status line showing agent, model, context window use, cost and MCP health at a glance
- Add files, folders, images and MCP resources as context with `@`; point the agent at a file's path with `#`
- Log in and out of providers, and start, stop, connect or disable MCP servers, from panels
- Resume, fork and roll back chats; flag checkpoints and jump between them
- Collapsible tool calls, thinking and sub-agent blocks
- Takes on your terminal's colour theme, dark or light (`NO_COLOR` turns colours off)

## Usage

```
eca-cli [--workspace <path>] [--model <model>] [--agent <agent>] [--trust] [--eca <path>]
```

Run `eca-cli --help` for details.

### Commands

Type `/` in an empty input to pick one. The picker also lists the ECA server's own commands (`/compact`, `/init`, `/context`, `/costs`, `/fork`, `/resume`, …), your skills and prompts.

| Command | Action |
|---|---|
| `/model` | Pick a model (also `Ctrl+L`) |
| `/agent` | Pick an agent |
| `/variant` | Pick the model's variant (e.g. reasoning effort) |
| `/rename <title>` | Rename the chat |
| `/new` | Start a fresh chat |
| `/chats` | Browse and resume previous chats |
| `/mcp` | MCP servers: status; start, stop, connect, enable, disable, log out |
| `/providers` | Providers: login state, models, quota; log in or out |
| `/jobs` | Background shell jobs |
| `/trust` | Toggle trust mode (run tool calls without asking) |
| `/rollback` | Roll the chat back to before one of your messages |
| `/flag <text>` | Flag your latest message as a checkpoint |
| `/flags` | Jump to, fork a new chat from, or remove a flag |
| `/timeline` | Jump to one of your messages or a flag |
| `/clear` | Clear the chat display |
| `/login` | Log in to a provider |
| `/help` | Show available commands |
| `/quit` | Exit |

### Keybindings

| Key | Action |
|---|---|
| `Enter` | Send message / toggle the focused block; while the agent works, steer it |
| `@` / `#` | Add a context / insert a path (at the start of a word) |
| `Alt+Enter` | While the agent works, queue a message for when it's done |
| `↑` on an empty input | While the agent works, take back what you steered or queued |
| `y` / `n` / `Y` | Approve / reject / approve and remember a tool call for the chat |
| `Esc` | Stop the running prompt, close a picker, clear focus |
| `Tab` / `Shift+Tab` | Cycle focus through chat blocks |
| `Alt+↑` / `Alt+↓` | Jump between top-level blocks |
| `Alt+c` / `Alt+o` | Collapse / expand all blocks |
| `Ctrl+T` | Show / hide the agent's task list |
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
