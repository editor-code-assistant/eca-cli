## Markdown Showcase

ECA is an **editor code assistant** that pairs with you *while you code*. Run `eca server` to start it, or visit [eca.dev](https://eca.dev) to learn more. Bare links like https://eca.dev/docs work too, and ~~old~~ text is struck.
A soft break keeps its line.

- Features
  - Chat with **tools**
  - Multiple `providers`
- A long bullet item that should wrap nicely with a hanging indent under the bullet marker, not under the left margin.

1. Install ECA
2. Configure a model
10. Start coding

- [x] Write the renderer
- [ ] Ship it

```clojure
(defn greet
  "Says hi."
  [name]
  ;; the classic
  (str "Hello, " name "!" :done 42))
```

| Tool  | Type   | Status  |
|-------|:------:|--------:|
| grep  | search | ✅ ok   |
| edit  | write  | ✅ ok   |
| shell | exec   | ⚠️ ask  |

> Good tools make good programmers better.
> — someone wise

### Third level heading

---

Done.
