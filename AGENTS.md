# BoomDB contributor and agent guide

## Course context

BoomDB is an analytical database engine built from scratch for ITU's *How to
Build Data Systems* course. The project grows in three parts:

1. Binary storage, CSV loading, partition statistics, and filtered scans.
2. Immutable partitions, copy-on-write deletes, versioned catalogues, and
   lightweight transactions.
3. Hash joins, cardinality estimation, and join-order optimization.

Later exercise specifications are authoritative. Do not implement speculative
features or choose architecture for a future exercise before its requirements
arrive.

## Academic ownership

AI assistance is allowed, but every contributor owns every line they merge.
Review generated changes, run the relevant checks, and be able to explain the
design, correctness, and trade-offs. Never invent benchmark results,
measurements, data provenance, or report claims.

## Current project baseline

- Java 25 or later and Apache Maven 3.9 or later are required.
- Production code uses package `dk.itu.boomdb`; tests use JUnit.
- Common commands are `mvn test`, `mvn verify`, and `mvn compile exec:java`.
- CI runs `mvn -B verify` on pull requests and pushes to `main`.

## Engineering workflow

1. Read the relevant exercise requirements and inspect existing code before
   proposing a change.
2. Keep changes focused. Reuse established patterns and the Java standard
   library where they fit, but never trade away validation, data durability,
   concurrency correctness, error handling, tests, or explicit requirements.
3. Add or adjust meaningful tests for changed behaviour. Run the smallest
   relevant check, then run `mvn verify` before opening a pull request.
4. Keep generated build output and runtime logs out of commits.

## Shared skills and AI learning log

Canonical repository skills live in `.agents/skills/`. Contributors whose AI
client needs explicit setup should register that directory with the client.

- Use `java-docs` for changed public/protected Java APIs and non-obvious
  internals. Do not add redundant Javadoc to self-evident code.
- Use `ponytail` for coding tasks to prefer the smallest correct solution.
  Course requirements, correctness, this guide, and explicit user requests
  take precedence over its minimalism.
- Use `ai-usage-log` whenever AI assists a tracked change to production code,
  tests, build/configuration, or scripts. It does not apply to explanation-only
  conversations or tasks with no tracked code change.

For covered work, create one entry in `docs/ai-usage/` after implementation
and validation, commit it with the change, and link it from the pull request.
Follow `docs/ai-usage/README.md`; do not record secrets, private text, raw
prompts, chain-of-thought, or raw tool output.
