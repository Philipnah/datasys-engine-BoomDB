# AI-assisted code learning log

Create one entry for every AI-assisted tracked change to production code,
tests, build/configuration, or scripts. Do not create entries for
explanation-only conversations, plans, or tasks that make no tracked code
change.

Create the entry after implementation and validation. Name it
`YYYY-MM-DD-<short-task-slug>.md`; if that name is taken, append `-2`, `-3`,
and so on. Commit it in the same change and link it from the pull request's AI
attribution section.

Use this template:

```markdown
# AI-assisted change: <short title>

- Date: YYYY-MM-DD
- Contributor: <name or Git user name>
- AI tool/model: <tool and model, if known>

## Request summary

<One sanitized paragraph describing what was asked. Do not paste the raw prompt.>

## AI response and implementation summary

<What the AI proposed or changed, and the resulting behaviour.>

## Changed files

- `<repository-relative path>` — <purpose>

## Validation

- `<command or check>` — <actual result>

## Ownership checkpoint

- [ ] I reviewed this change and can explain its design, correctness, and trade-offs.
```

Do not include credentials, tokens, personal/private text, raw prompts,
chain-of-thought, or raw tool output. The ownership checkbox must remain
unchecked until a human contributor has completed that review.
