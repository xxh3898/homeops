# Security Policy

## Supported versions

Until the first stable release, only the latest commit on the default branch is eligible for security fixes. Pre-release interfaces may change without backward compatibility.

## Reporting a vulnerability

Do not open a public issue containing an exploit, secret, private address, Tailnet detail, container environment, log excerpt with credentials, or host-specific path. Use the repository owner's private GitHub security advisory channel once the public repository is created.

## Security boundary

HomeOps is designed for a private tailnet and a single administrator. Source availability does not make direct internet exposure safe. The project does not provide:

- a public status page;
- multi-tenant isolation;
- arbitrary shell, Compose, or Docker command execution;
- protection after the macOS account or Docker Engine is compromised.

The native Agent has strong Docker control potential. Run it without `sudo`, configure only the Docker socket and paths you intend to expose, and leave control capabilities disabled unless the documented allowlist contract is complete.

