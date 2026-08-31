---
name: ai-usage-log
description: Create one sanitized learning-log entry after AI-assisted tracked changes to BoomDB code, tests, build/configuration, or scripts. Do not use for explanation-only or no-change requests.
---

# AI learning log

Use this skill when an AI-assisted request creates or changes tracked
production code, tests, build/configuration, or scripts. Do not use it for
explanation-only conversations, plans, or requests that do not change tracked
code.

After implementation and validation, create exactly one new file at
`docs/ai-usage/YYYY-MM-DD-<short-task-slug>.md`. If that name already exists,
append `-2`, `-3`, and so on. Commit the entry with the associated change and
link it from the pull request's AI attribution section.

Use the format in `docs/ai-usage/README.md`. Summarize the request rather than
copying it verbatim. Record the implementation/result, changed files, and
actual validation commands and outcomes. Never include credentials, private
text, raw prompts, chain-of-thought, or raw tool output. Leave the contributor
ownership checkbox unchecked; only a human contributor may check it after
reviewing the change and confirming they can explain it.
