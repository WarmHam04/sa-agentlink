# Security Policy

## Reporting a vulnerability

Please do not open a public issue for a vulnerability involving command execution, credential exposure, SSRF, authorization bypass, or sandbox escape. Use GitHub's private vulnerability reporting for this repository.

## Trust model

SA-AgentLink can launch local coding agents and contact remote Agent endpoints. Treat workflow authors, AgentCard publishers, runtime executables, and working directories as separate trust boundaries.

Default protections include:

- no shell interpolation for CLI arguments;
- read-only or plan-mode runtime defaults;
- workspace-root confinement;
- execution timeout and bounded Artifact size;
- private-network blocking for discovered remote endpoints;
- bounded retry counts.

These controls are defense in depth, not a replacement for containers, operating-system isolation, egress policies, or provider-specific sandboxing. Never run untrusted repositories with edit permissions on a host containing secrets.
