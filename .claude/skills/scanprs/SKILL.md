---
name: scanprs
description: Read-only sweep of every repository in a GitHub org that lists all open pull requests, loudly flags any PR that GitHub Copilot has not reviewed or has only reviewed at a stale commit, and reports what changed since the previous sweep. Use this whenever the user says /scanprs, "scan prs", "scan the org", "re-scan", "check the org", or asks anything about which PRs are open across their org, what has changed since last time, PR review coverage, or which pull requests are still missing a Copilot review — even when they do not name this skill or list the repositories themselves.
---

# Org-wide open PR scan

Work spreads across many repositories, and PRs land in them at all hours from
agents, Dependabot, and people. Nobody watches every tab. This skill answers
two questions in one pass: what is open right now, and what is sitting there
without real review coverage.

The second question is the one that matters. An unreviewed PR is not merely
unfinished — it is invisible. It looks identical to a reviewed one in a plain
listing, so it drifts. Surfacing those loudly, at the top, is the point of the
report format below.

## Step 0 — make the tools available

This skill runs in many contexts: an interactive CLI session, a remote or web
session, a subagent with a narrowed toolset, a scheduled run. The tools it
wants are not always loaded, so check before assuming failure.

If a needed tool is absent from your tool list, it may be **deferred** rather
than missing — load its schema first:

```
ToolSearch: select:mcp__github__list_pull_requests,mcp__github__pull_request_read
ToolSearch: select:mcp__Claude_Code_Remote__list_repos,mcp__Claude_Code_Remote__add_repo
```

MCP servers also drop and reconnect mid-session. A "no such tool available"
error immediately after a disconnect notice usually resolves by re-running
ToolSearch once the server is back — retry once before concluding a capability
is gone.

If the GitHub MCP tools genuinely do not exist but a `gh` CLI does, `gh pr
list` and `gh api` do the same work. Adapt rather than abandoning the scan.

**If no path to GitHub exists at all, say so explicitly.** Never let "I could
not check" render as "no open PRs" — a silent empty scan is the one failure
mode that actively misleads, because it looks like good news.

## Step 1 — discover the repositories

Do not assume a fixed list. Try in this order, and report which tier you used:

1. `mcp__Claude_Code_Remote__list_repos` — authoritative for what this session
   can reach. Paginate while `has_more` is true.
2. `mcp__github__search_repositories` with `org:<org>` — when the session has
   GitHub MCP but no remote-session tooling.
3. The known QuasarApps set below — last resort, and label it as stale:

```
Quasar-Sample-App   Aquifer      CSA-App        Robinhood-Agent   Diamonds
Pulsar              Sanis        Habitatt       MyCSAApp_Old      Clique
AluminumSolutions   mycsamobileapp   Habitatt_Old   EIAcademy     Sparkle
```

Runtime discovery is what keeps this scan honest: repos created or deleted
since the last run are picked up without editing the skill. "Discovered 17
repos" and "fell back to a hardcoded list of 15" are very different claims
about coverage, so state which one happened.

Further guidance:

- Filter to the org in question. If the user named one, use it; otherwise infer
  from the current repo's `origin` remote. A scan of the wrong org, silently
  answering the wrong question, is the worst outcome here — so name the org you
  swept.
- **Include archived repos.** They rarely hold open PRs, but "rarely" is not
  "never", and skipping them makes the coverage claim false.
- Ignore `can_push`. This skill only reads.

A repo can be visible to discovery but not yet in the session's GitHub scope.
When a call fails for that reason, attach it with
`mcp__Claude_Code_Remote__add_repo` using `access: "read"` — this skill never
writes, and requesting push scope triggers permission prompts the user should
not have to answer. Repos already attached do not need re-attaching.

## Step 2 — list open PRs

Call `mcp__github__list_pull_requests` once per repo with `state: "open"`,
issuing them all in a single message so they run concurrently.

Request only what the report needs — omitting `body` matters most, since PR
descriptions dwarf every other field:

```
fields: ["number", "title", "html_url", "user", "draft", "created_at", "updated_at", "head"]
```

`head` carries the current commit SHA, which Step 3 needs for staleness.

If a repo returns a full page, there may be more — paginate until short. A
truncated sweep reporting itself as complete is worse than one that admits it
stopped.

When per-repo listing is unavailable, `mcp__github__search_pull_requests` with
`org:<org> is:pr is:open` covers everything in one call. Treat it as a fallback
rather than the default: it is subject to search-index lag and only sees what
the token can see. Say which method produced the numbers.

## Step 3 — assess review coverage

For every open PR, call `mcp__github__pull_request_read` with
`method: "get_reviews"`, batched in one message.

Sort each PR into one of four states. The distinction is what makes the report
actionable — "no review" conflates situations with different fixes:

| State | Meaning | What it implies |
|---|---|---|
| ✅ Reviewed | Copilot review exists at the current head SHA | Covered |
| ⏳ Stale | Copilot reviewed, but at an older commit | Code changed after review; needs a re-run |
| 🚩 Missing | No Copilot review at all | Needs a review request |
| 🚩 Pending | Review requested, none delivered yet | Waiting; note how long |

A review counts as Copilot's when `user.login` is
`copilot-pull-request-reviewer[bot]` or `Copilot`. Match generously — bot login
formats change, and a scan that quietly stopped recognizing Copilot would fire
false alarms across the entire org.

**Staleness** deserves the extra care: compare the review's `commit_id` against
the PR's current `head.sha`. A review of a commit that has since been rewritten
is reassuring and wrong, which is more dangerous than an obviously missing one.

Two traps, both learned the hard way:

- **Draft status does not exempt a PR.** Copilot does review drafts. Do not
  explain away a missing review by pointing at the draft flag — report the gap
  and note draft status as context, not as the cause.
- **A Copilot review moves `updated_at`.** When a PR's timestamp jumps and a
  review just landed, those are one event, not two. Compare the review's
  `submitted_at` to `updated_at` before reporting them separately.

Reviews carry real content. When Copilot flags something concrete — a stale
comment, a skipped file, a suggested follow-up — one line about it is worth far
more than "reviewed: yes", because it tells the user whether the PR is actually
clear to merge.

## Step 4 — compute deltas

Read the previous scan from `~/.claude/scanprs-state/last-scan.json`. That path
is deliberately independent of where this skill is installed, so global and
project-local copies share one history instead of disagreeing.

If the file is missing, this is a baseline scan — say so plainly rather than
inventing changes against nothing. If the path is unreadable or unwritable, as
in a sandboxed subagent, continue anyway: report the scan as a baseline and
note that deltas were unavailable. Losing history is a degradation, not a
failure, and it must never abort the scan.

Report:

- **New PRs** — present now, absent before
- **No longer open** — present before, absent now
- **Touched** — `updated_at` changed
- **Coverage flips** — gained a review, went stale, or still missing
- **Repo set changes** — repos added or removed since the last run

On PRs that vanished: `state: "open"` only proves they left the open set. It
cannot distinguish merged from closed, so do not guess. If the difference
matters, confirm with `pull_request_read` / `method: "get"`.

Afterward write the current scan back, creating the directory if needed. Store
per PR: repo, number, title, `updated_at`, head SHA, draft flag, and coverage
state — plus the repo list and a timestamp, so the next run can report
org-level changes and say how long it has been.

## Step 5 — the report

Lead with the failure, not the inventory. Someone skimming on a phone should
hit the bad news first.

```markdown
## 🚩 Needs review — N of M open PRs

- **<Repo> [#<num>](<url>)** — `<title>` — <missing | stale since <sha> | pending <duration>>

| Repo | PR | Coverage | Updated |
|---|---|---|---|
| <Repo> | [#<num>](<url>) | ✅ / ⏳ Stale / 🚩 Missing | <time> |

**Deltas:** <new / closed / touched / coverage flips / repo changes, or "no changes">
**Swept:** <N> repos in <org> via <discovery tier>; <M> had open PRs
```

If every open PR is covered, say so in one explicit line rather than omitting
the section. An absent warning is ambiguous; a stated all-clear is not.

**Always render the PR number as a clickable markdown link**, in tables and in
prose, using `html_url`. A bare `#85` makes the reader go hunting, and this
report exists to be acted on.

Scale the shape to the result. A handful of PRs deserves the full table; sixty
across twenty repos deserves grouping by repo, with flagged ones still listed
individually up top. The format is a starting point, not a cage — what must
survive is bad-news-first, clickable links, and honest coverage counts.

**When running as a subagent**, your final text is the return value, not a
message to a person. Return the report itself — no preamble, no "here you go" —
so the caller can use it directly.

## Guardrails

This skill reads. It does not push, commit, comment, request reviewers, mark
drafts ready, approve, or merge — every one of those is someone else's call,
and several are hard to undo.

The temptation grows with a smarter scan: it surfaces a PR with no reviewer,
correctly diagnoses that a review request would fix it, and that is one API
call away. Do not make it. Surface the gap and let the user decide. If they
want a review requested, they will say so — and that is a different, explicitly
authorized action.
