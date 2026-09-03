# Code-change learning log

Create one entry for every AI-assisted tracked change to production code,
tests, build/configuration, or scripts. Also create an entry when a contributor
explicitly asks to record a human-authored tracked code change. Do not create
entries automatically for unaided changes, and do not create entries for
explanation-only conversations, plans, or tasks that make no tracked code
change.

Create the entry after implementation and validation. Name it
`YYYY-MM-DD-<short-task-slug>.md`; if that name is taken, append `-2`, `-3`,
and so on. Commit it in the same change and link it from the pull request's AI
attribution section.

Use this template:

```markdown
# Code change: <short title>

- Date: YYYY-MM-DD
- Contributor: <name or Git user name>
- Assistance: AI-assisted | Human-authored, logged on request
- AI tool/model: <tool and model, if known; otherwise N/A>

## Request summary

<One sanitized paragraph describing what was asked. Do not paste the raw prompt.>

## Change and assistance summary

<What changed, the resulting behaviour, and—if used—what the AI contributed.>

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
