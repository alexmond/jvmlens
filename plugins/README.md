# jvmlens — Claude Code skills

This repo doubles as a small [Claude Code](https://claude.com/claude-code) plugin
marketplace: two skills that teach an agent to drive jvmlens.

| Plugin | What it does |
|---|---|
| **jvmlens-perf** | the dev-time **optimize→measure loop** — capture a JFR, analyze ranked hot paths + allocation (source-attributed), fix the top lever, diff before/after on absolute weight, optionally gate regressions. Pairs with JMH. |
| **jvmlens-monitor** | the **long-running monitor** — attach the agent with `history=` so it appends one compact CPU/memory/wait sample per interval, let it run for days, then `jvmlens trend` reduces the run to a change-over-time digest. Also one-shot analyze, live `profile <pid>`, and an MCP server. |

## Install

```
/plugin marketplace add alexmond/jvmlens
/plugin install jvmlens-perf@jvmlens
/plugin install jvmlens-monitor@jvmlens
```

Both skills run jvmlens **locally** — it never calls an LLM and never ships recordings anywhere.

## Layout

```
.claude-plugin/marketplace.json         # the marketplace manifest
plugins/<name>/.claude-plugin/plugin.json
plugins/<name>/skills/<name>/SKILL.md   # the published skill
```

## Local overlays (maintainer-only, gitignored)

Each skill optionally reads two machine-local files sitting next to it — **not** part of the
published plugin, and gitignored so they never reach the marketplace:

- `LEARNINGS.md` — an append-only log of field experience across projects.
- `extensions/*.md` — local addenda (e.g. a private deploy convention, or a dogfooding /
  file-findings-upstream loop) that only make sense on the maintainer's machine.

The public `SKILL.md` ends with a neutral one-line hook: *"if a `LEARNINGS.md` or
`extensions/*.md` sits next to this skill, read it first."* On a public install neither exists,
so the skill is self-contained; on the maintainer's machine (the skill dir is symlinked from
`~/.claude/skills/`) the overlay adds the private behaviour. Single source of truth, no drift.
