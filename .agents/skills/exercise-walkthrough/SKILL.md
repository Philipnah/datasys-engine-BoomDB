---
name: exercise-walkthrough
description: Maintain a one-page Markdown walkthrough per BoomDB exercise after every agent-made project change, including code, tests, configuration, scripts, and documentation. Use for explicit walkthrough requests too; skip explanation-only work and updates solely to generated walkthroughs or AI usage logs.
---

# Exercise walkthrough

Help a human reviewer understand what changed for an exercise, why it changed,
where to read the implementation, and what evidence supports it. Maintain one
guide per exercise; do not create a new guide for each request or append a
chronological change log.

## When and where

After making and validating project changes, create or update
`docs/walkthroughs/exercise-N.md`, where `N` is the exercise number. Include
follow-up fixes, refactors, tests, build/configuration, scripts, and documentation
in the same exercise's guide. Updating a walkthrough or an AI usage log alone
does not trigger another walkthrough update.

Identify the exercise from the user's task and the relevant specification in
`exercise-descriptions/`. Read that specification and any existing guide before
writing. Do not infer the exercise from the highest numbered specification or
the older course outline in `AGENTS.md`. For shared project changes, use the
active exercise when the task establishes one. If the exercise is unclear, ask
the user which guide to update while continuing work that does not depend on
the answer. A change spanning multiple exercises updates each affected guide.

## Write the guide

- Inspect the actual implementation, relevant tests, and the task's changes.
  Git may be read to understand changes; do not stage, commit, tag, or otherwise
  change Git state. Exclude unrelated pre-existing edits from claims about the
  current task.
- Explain the current implementation accumulated for the exercise, incorporating
  the latest change into the relevant section. Preserve still-correct context
  and existing comments. If a comment is stale, flag it to the user rather than
  removing it.
- Target roughly 350–500 words, including headings and bullets. Markdown has no
  fixed page size; use this as the one-page reading budget. Prefer short prose
  and a numbered reading path over inventories of every changed file. Link to
  deeper design notes or AI usage entries for details instead of expanding the
  guide into a report.
- Connect each major change to the exercise requirement it serves. Explain
  behavior and design choices in plain language, including one concrete flow
  or before/after example when it helps the reviewer.
- Give an ordered path through the important files and symbols: entry point,
  core behavior, and tests. Use relative Markdown links from the guide to real
  repository files, and name methods or classes so links remain useful as line
  numbers move. Include documentation or configuration when that is the change.
- Report only checks actually performed, their outcomes, and what they cover.
  Identify which checks were run for the latest change; distinguish earlier
  validation if it is retained. Say when validation failed, was blocked, or was
  not run. Do not claim exercise completion, CI success, or benchmark results
  without evidence. Mark partial implementation and remaining requirements.
- State material trade-offs, limitations, and the areas a reviewer should check.
  Keep the walkthrough focused on the implementation; the AI usage log records
  attribution and learning separately. Avoid private text, secrets, raw prompts,
  and raw tool output.

Use this compact structure, adapting the content to the exercise:

```markdown
# Exercise N: <title> — walkthrough

Updated: YYYY-MM-DD

## What changed and why

<Exercise goal, implemented scope, latest change, and resulting behavior.>

## Walk through the changes

1. [<file>](<relative-path>) — <symbol, role, and reason for the change>.
2. [<file>](<relative-path>) — <next step in the flow and key design choice>.
3. [<test>](<relative-path>) — <behavior or edge case it verifies>.

## Validation

<Actual checks and outcomes for the latest change; note any gaps.>

## Review focus and remaining work

<Important correctness questions, trade-offs, and unfinished requirements.>
```

## Finish

Check that every link resolves, the reading order matches the actual code, the
guide fits the reading budget, and all claims have evidence. Do not rerun an
entire test suite solely because the guide was edited. Link the updated guide
in the final response and in a pull request description if creating or updating
one is already authorized. This skill does not authorize Git changes, creating
a pull request, or publishing anything.
