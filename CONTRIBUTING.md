# Contributing

Thanks for helping improve SA-AgentLink.

## Development

1. Use Java 17 or newer and Maven 3.8+.
2. Create a focused branch from `main`.
3. Run `mvn verify` before opening a pull request.
4. Add tests for behavior changes, especially adapter parsing, task-state transitions, retry behavior, and DAG execution.
5. Update README or architecture documentation when changing public APIs or trust boundaries.

## Adapter requirements

A runtime adapter must:

- implement `AgentRuntimeAdapter` without leaking vendor objects into the domain layer;
- invoke processes with argument arrays, never shell-concatenated strings;
- default to the least-privileged execution mode;
- emit normalized `RuntimeEvent` values;
- return one bounded Artifact or a clear failure;
- document authentication and session-persistence behavior;
- include fixture-based parser tests that do not require paid API calls.

Do not commit CLI credentials, session files, API keys, captured prompts, or raw model transcripts.

## Pull requests

Keep pull requests small enough to review. Explain:

- the user-visible behavior;
- protocol or adapter compatibility impact;
- security implications;
- verification performed.
