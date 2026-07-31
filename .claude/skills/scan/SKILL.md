---
name: scan
description: Read-only sweep of every repository in a GitHub org that lists all open pull requests, loudly flags any PR that GitHub Copilot has not reviewed or has only reviewed at a stale commit, and reports what changed since the previous sweep. Use this whenever the user says /scan, "scan", "scan the org", "re-scan", "check the org", or asks anything about which PRs are open across their org, what has changed since last time, PR review coverage, or which pull requests are still missing a Copilot review — even when they do not name this skill or list the repositories themselves.
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

## Step 0 — discover the repositories

Do not assume a fixed list. Call `mcp__Claude_Code_Remote__list_repos` and use
what it returns, so repos created or deleted since the last run are picked up
automatically. That is the difference between a report that stays true and one
that quietly rots.

Guidance on the result:

- Filter to the org in question. If the user named one, use it. Otherwise infer
  from the current repo's `origin` remote, and say which org you swept — a scan
  of the wrong org silently answering the wrong question is the worst outcome
  here.
- **Include archived repos.** They rarely hold open PRs, but "rarely" is not
  "never", and dropping them makes the coverage claim false.
- Ignore `can_push`. This skill only reads.
- Paginate if `has_more` is true.

If discovery fails or returns nothing, fall back to this known QuasarApps set
so a broken tool call degrades into a slightly stale scan rather than no scan:

```
Quasar-Sample-App   Aquifer      CSA-App        Robinhood-Agent   Diamonds
Pulsar              Sanis        Habitatt       MyCSAApp_Old      Clique
AluminumSolutions   mycsamobileapp   Habitatt_Old   EIAcademy     Sparkle
```

Say plainly which source you used. "Discovered 17 repos" and "fell back to a
15-repo list from January" are very different claims about coverage.

A repo can be visible to `list_repos` but not yet in the session's GitHub
scope. When a call fails for that reason, attach it with
`mcp__Claude_Code_Remote__add_repo` using `access: "read"` — this skill never
writes, and requesting push scope produces permission prompts the user should
not have to answer. Repos already attached do not need re-attaching; calling
`add_repo` again just burns a prompt.

## Step 1 — list open PRs

Call `mcp__github__list_pull_requests` once per repo with `state: "open"`,
issuing them all in a single message so they run concurrently.

Request only what the report needs — omitting `body` matters most, since PR
descriptions dwarf every other field:

```
fields: ["number", "title", "html_url", "user", "draft", "created_at", "updated_at", "head"]
```

`head` carries the current commit SHA, which Step 2 needs for staleness.

If a repo returns a full page (`perPage` items), there may be more — paginate
until short. A truncated sweep that reports itself as complete is worse than
one that admits it stopped.

## Step 2 — assess review coverage

For every open PR, call `mcp__github__pull_request_read` with
`method: "get_reviews"`, batched in one message.

Sort each PR into one of four states. The distinction is what makes the report
actionable — "no review" conflates situations with very different fixes:

| State | Meaning | What it implies |
|---|---|---|
| ✅ Reviewed | Copilot review exists at the current head SHA | Covered |
| ⏳ Stale | Copilot reviewed, but at an older commit | Code changed after review; needs a re-run |
| 🚩 Missing | No Copilot review at all | Needs a review request |
| 🚩 Pending | Review requested, none delivered yet | Waiting; worth noting how long |

A review counts as Copilot's when `user.login` is
`copilot-pull-request-reviewer[bot]` or `Copilot`. Match generously — bot login
formats change, and a scan that silently stops recognizing Copilot would report
false alarms across the whole org.

**Staleness** is worth the extra care: compare the review's `commit_id` against
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

## Step 3 — compute deltas

Read the previous scan from `~/.claude/scan-state/last-scan.json`. That path is
deliberately independent of where this skill is installed, so a global copy and
a project-local copy share one history instead of disagreeing.

If the file is missing, this is a baseline scan — say so plainly rather than
inventing changes against nothing.

Report:

- **New PRs** — present now, absent before
- **No longer open** — present before, absent now
- **Touched** — `updated_at` changed
- **Coverage flips** — gained a review, went stale, or still missing
- **Repo set changes** — repos added to or removed from the org since last run

On PRs that vanished: `state: "open"` only proves they left the open set. It
cannot distinguish merged from closed, so do not guess. If the difference
matters, confirm with `pull_request_read` / `method: "get"` and read the real
state.

Afterward write the current scan back to that path, creating the directory if
needed. Store per PR: repo, number, title, `updated_at`, head SHA, draft flag,
and coverage state. Also store the repo list and a timestamp, so the next run
can report org-level changes and say how long it has been.

## Step 4 — the report

Lead with the failure, not the inventory. Someone skimming on a phone should
hit the bad news first.

```markdown
## 🚩 Needs review — N of M open PRs

- **<Repo> [#<num>](<url>)** — `<title>` — <missing | stale since <sha> | pending <duration>>

| Repo | PR | Coverage | Updated |
|---|---|---|---|
| <Repo> | [#<num>](<url>) | ✅ / ⏳ Stale / 🚩 Missing | <time> |

**Deltas:** <new / closed / touched / coverage flips / repo changes, or "no changes">
**Swept:** <N> repos via <discovery source>; <M> had open PRs
```

If every open PR is covered, say so in one explicit line rather than omitting
the section. An absent warning is ambiguous; a stated all-clear is not.

**Always render the PR number as a clickable markdown link**, in tables and in
prose, using `html_url`. A bare `#85` makes the reader go hunting, and this
report exists to be acted on.

Scale the shape to the result. A handful of PRs deserves the full table; sixty
across twenty repos deserves grouping by repo, with the flagged ones still
listed individually up top. The format above is a starting point, not a cage —
what must survive is bad-news-first, clickable links, and honest coverage
counts.

## Guardrails

This skill reads. It does not push, commit, comment, request reviewers, mark
drafts ready, approve, or merge — every one of those is someone else's call,
and several are hard to undo.

The temptation is real and grows with a smarter scan: it surfaces a PR with no
reviewer, correctly diagnoses that a review request would fix it, and that is
one API call away. Do not make it. Surface the gap and let the user decide. If
they want a review requested, they will say so — and that is a different,
explicitly authorized action.
