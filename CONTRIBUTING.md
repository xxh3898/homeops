# Contributing

HomeOps uses `dev` as the integration branch and `main` as the release branch.

1. Create a focused branch from current `dev`.
2. Add or update tests for production behavior.
3. Open a pull request into `dev` for integration work.
4. Release changes through a reviewed `dev` to `main` pull request.

Pull requests from forks run read-only validation jobs only. They never receive deployment credentials and never execute on the HomeOps operator's Mac.

Do not include real `.env` values, certificates, hostnames, personal email addresses, private IP addresses, Tailnet data, webhook URLs, Docker inspect output, or container environment values in commits, issues, or test fixtures.

