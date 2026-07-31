---
name: scan
description: Read-only sweep of every QuasarApps org repository that lists all open pull requests, loudly flags any PR that GitHub Copilot has not reviewed, and reports what changed since the previous sweep. Use this whenever the user says /scan, "scan", "scan the org", "re-scan", "check the org", or asks anything about which PRs are open across the org, what has changed since last time, PR review coverage, or which pull requests are still missing a Copilot review — even when they do not name this skill or list the repositories themselves.
---

# Org-wide open PR scan

The QuasarApps org spreads work across fifteen repositories, and PRs land in
them at all hours from agents, Dependabot, and people. Nobody watches all
fifteen tabs. This skill answers two questions in one pass: what is open right
now, and what is sitting there without a Copilot review.

The second question is the one that matters most. An unreviewed PR is not
merely unfinished — it is invisible. It looks the same as a reviewed one in a
plain listing, so it drifts. Surfacing those loudly, at the top, is the whole
point of the report format below.

## The repository set

Sweep all fifteen:

```
Quasar-Sample-App   Aquifer      CSA-App        Robinhood-Agent   Diamonds
Pulsar              Sanis        Habitatt       MyCSAApp_Old      Clique
AluminumSolutions   mycsamobileapp   Habitatt_Old   EIAcademy     Sparkle
```

Owner is `QuasarApps` for every one. Two of them — `MyCSAApp_Old` and
`Habitatt_Old` — are archived and will essentially always return nothing, but
sweep them anyway: an archived repo can still hold an open PR, and silently
dropping repos from the sweep is how a report starts lying about coverage.

This list is a snapshot. If the user mentions a new repo, or a call fails
because a repo is not in the session's GitHub scope, attach it with
`mcp__Claude_Code_Remote__add_repo` using `access: "read"` — this skill never
writes, so push credentials are unnecessary and asking for them produces
permission prompts the user should not have to answer. Repos already attached
in the session do not need re-attaching; calling `add_repo` again just burns a
prompt.

## Step 1 — list open PRs

Call `mcp__github__list_pull_requests` once per repo with `state: "open"`.
Issue all fifteen in a single message so they run concurrently; serially this
takes fifteen round trips for no reason.

Request only the fields the report needs, to keep the responses small:

```
fields: ["number", "title", "html_url", "user", "draft", "created_at", "updated_at"]
```

Omitting `body` matters — PR descriptions are the largest field by far and
nothing here uses them.

## Step 2 — check Copilot review coverage

For every open PR found, call `mcp__github__pull_request_read` with
`method: "get_reviews"`. Batch these in one message too.

A PR counts as Copilot-reviewed when at least one returned review has
`user.login` equal to `copilot-pull-request-reviewer[bot]` or `Copilot`. An
empty array means no reviews from anyone at all.

Two traps worth knowing, both learned the hard way:

- **Draft status does not exempt a PR.** Copilot does review drafts. Do not
  explain away a missing review by pointing at the draft flag — report the gap
  and note the draft status as context, not as a cause.
- **A Copilot review moves `updated_at`.** When a PR's timestamp jumps and its
  review just landed, those are the same event, not two. Check the review's
  `submitted_at` against `updated_at` before reporting them as separate news.

Reviews carry real content. When Copilot flags something concrete — a stale
comment, a missed file, a suggested follow-up — a one-line mention is worth far
more than "reviewed: yes", because it tells the user whether the PR is actually
clear to merge.

## Step 3 — compute deltas

Read the previous scan from `.claude/skills/scan/state/last-scan.json`. If it
is missing, this is a baseline scan — say so plainly rather than inventing
changes against nothing.

Compare and report:

- **New PRs** — present now, absent before
- **No longer open** — present before, absent now
- **Touched** — `updated_at` changed
- **Review status flips** — newly gained a Copilot review, or still lacks one

On PRs that vanished: `state: "open"` only proves they left the open set. It
cannot distinguish merged from closed, so do not guess. If the distinction
matters to the user, confirm with `pull_request_read` / `method: "get"` and
read the real state.

After reporting, write the current scan back to that state file so the next run
has something to compare against. Store per PR: repo, number, title,
`updated_at`, draft flag, and whether Copilot reviewed it.

## Step 4 — the report

Lead with the failure, not the inventory. A reader skimming on their phone
should learn the bad news before they learn anything else.

```markdown
## 🚩 Not reviewed by Copilot — N of M open PRs

- **<Repo> [#<num>](<url>)** — `<title>` — opened <when>, <review state>

| Repo | PR | Copilot reviewed | Updated |
|---|---|---|---|
| <Repo> | [#<num>](<url>) | 🚩 No / ✅ Yes | <time> |

**Deltas:** <new / closed / touched / review flips, or "no changes">
```

If every open PR is covered, say so in one explicit line — "all N open PRs have
a Copilot review" — rather than silently omitting the section. An absent
warning is ambiguous; a stated all-clear is not.

**Always render the PR number as a clickable markdown link**, in tables and in
prose, using the `html_url` from the API. A bare `#85` forces the reader to go
find it, and this report exists to be acted on. Same for repos with zero open
PRs: name them in a single summary line so coverage of all fifteen is visible.

## Guardrails

This skill reads. It does not push, commit, comment, request reviewers, mark
drafts ready, approve, or merge — every one of those is someone else's call,
and several are hard to undo.

The temptation is real: the scan surfaces a PR with no reviewer, and requesting
one is a single API call away. Do not make it. Surface the gap, and let the
user decide. If they want a review requested, they will say so — and that is a
different, explicitly authorized action.
