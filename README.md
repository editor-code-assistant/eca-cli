# ECA CLI

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

ECA (Editor Code Assistant) CLI is an AI-powered pair-programming client for your terminal, no editor required.
It connects to an external `eca` server process to provide interactive chat, tool calls with live diffs, context management and more, all keyboard-driven.

For more details about ECA, features and configuration, check [ECA server](https://github.com/editor-code-assistant/eca).

This CLI will auto download `eca` and manage the process.

## Requirements

- [Babashka](https://babashka.org/) 1.12.215 or later

## Quickstart

```bash
git clone https://github.com/editor-code-assistant/eca-cli.git
cd eca-cli
bb run
```

To use it on any project, alias it and run it from the project directory:

```bash
alias eca-cli='bb --config /path/to/eca-cli/bb.edn run'
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
bb run [--workspace <path>] [--model <model>] [--agent <agent>] [--trust] [--eca <path>]
```

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

```bash
bb test    # unit tests
bb nrepl   # nREPL on port 7888
```

See [CLAUDE.md](./CLAUDE.md) for the architecture.

## Contributing 💙

Contributions are very welcome, please open an issue for discussion or a pull request.

## License

Apache License 2.0, see [LICENSE](./LICENSE).
