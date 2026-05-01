# eca-cli

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)

A [Babashka](https://babashka.org/) TUI client for [ECA (Editor Code Assistant)](https://eca.dev/).

eca-cli speaks the same JSON-RPC protocol as the editor plugins (eca-emacs, eca-nvim, eca-desktop) but renders entirely in the terminal. The server does the heavy lifting — eca-cli is a focused, scriptable, keyboard-driven front end.

> **Status:** Early development. Roadmap phases 1a–5 complete. See [docs/roadmap.md](./docs/roadmap.md) for what's in and what's next.

## Requirements

- [Babashka](https://babashka.org/) 1.12.215 or later
- An ECA server binary (auto-downloaded by `bb upgrade-eca`, or supply your own with `--eca`)
- A 256-colour terminal with mouse-reporting support (iTerm2, Ghostty, Kitty, gnome-terminal, modern tmux all work)

## Quick start

```bash
git clone https://github.com/editor-code-assistant/eca-cli.git
cd eca-cli
bb upgrade-eca   # one-time: download the pinned ECA server binary
bb run           # launch the TUI
```

No separate build step — `bb run` compiles and runs directly.

### CLI flags

```
bb run --eca /path/to/eca         # use a specific ECA binary
bb run --workspace /path          # set workspace root (default: cwd)
bb run --model <model>            # pre-select a model
bb run --agent <agent>            # pre-select an agent
bb run --trust                    # auto-approve all tool calls
```

## In-app commands

Type these into the input and press Enter:

| Command | Action |
|---|---|
| `/model` | Open model picker (also Ctrl+L) |
| `/agent` | Open agent picker |
| `/new` | Start a fresh chat (deletes current) |
| `/sessions` | Browse and resume previous chats |
| `/clear` | Clear the chat display (local only) |
| `/help` | Show available commands |
| `/login` | Manually trigger provider login |
| `/quit` | Exit eca-cli |

Typing `/` in an empty input opens an autocomplete picker for these commands.

## Keybindings

| Key | Action |
|---|---|
| `Enter` | Send message (in `:ready`) / toggle expanded block (when focused) |
| `Tab` / `Shift+Tab` | Cycle focus through chat blocks (sub-items included) |
| `Alt+↑` / `Alt+↓` | Jump to previous / next top-level block (skips sub-items) |
| `Alt+g` / `Alt+G` | Focus first / last focusable block |
| `Alt+c` / `Alt+o` | Collapse all / expand all focusable blocks |
| `↑` / `↓` | Navigate focus when active; recall input history when not |
| `Esc` | Cancel a running prompt; cancel a picker; clear focus |
| `y` / `n` / `Y` | Approve / reject / approve-and-trust a tool call |
| `PgUp` / `PgDn` | Scroll chat history |
| `Mouse wheel` | Scroll (3 lines per tick) |
| `Ctrl+C` | Quit |
| `Ctrl+L` | Open model picker |

Alt-prefixed bindings are supported on iTerm2, Ghostty, Kitty, and modern tmux (with `xterm-keys on`). Terminal.app on macOS sends Alt as ESC-prefix by default — set "Use Option as Meta key" in Profiles → Keyboard for Alt to register as a modifier.

## Architecture

eca-cli is a Babashka TUI client built on the [charm.clj](https://github.com/timokramer/charm.clj) Elm-loop runtime. The LLM pulls context via ECA's built-in tools — the user just sends messages.

```
ECA process stdout
  → reader thread (server.clj)
  → LinkedBlockingQueue
  → drain-queue-cmd (state.clj)
  → :eca-tick message
  → update-state dispatcher
  → per-feature handle-key / handle-msg
  → view.clj render
```

Each feature owns a namespace: `chat.clj`, `commands.clj`, `picker.clj`, `login.clj`, `sessions.clj`. `state.clj` is the dispatcher that delegates to them. See [CLAUDE.md](./CLAUDE.md) for the in-depth architecture overview and [docs/refactor/](./docs/refactor/) for the structural-refactor history.

## Development

```bash
bb test            # run unit tests
bb itest           # run integration tests (requires tmux + ECA on PATH)
bb nrepl           # start nREPL on port 7888
```

ECA server logs go to `~/.cache/eca/eca-cli.log` — tail this when debugging server-side issues.

## Roadmap

See [docs/roadmap.md](./docs/roadmap.md) for the phased development plan. Current focus after Phase 5: ECA editor-plugin checklist alignment (tool-call diff display, MCP integration) followed by markdown rendering and message steering.

## Related

- [ECA core server](https://github.com/editor-code-assistant/eca)
- [eca-emacs](https://github.com/editor-code-assistant/eca-emacs)
- [eca-nvim](https://github.com/editor-code-assistant/eca-nvim)
- [eca-desktop](https://github.com/editor-code-assistant/eca-desktop)

## License

Apache License 2.0 — see [LICENSE](./LICENSE).
