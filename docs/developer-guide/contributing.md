# Contributing

LLMage is a fork of XMage, and contributions are reviewed with that in mind: a change should be easy to carry forward across upstream updates, narrow in scope, and covered by a test that fails without it. This page describes the public workflow, the coding rules that matter most in this codebase, and what a reviewer will look for.

> **Who this is for:** anyone preparing a bug report, a patch, or a pull request against the public repository.

## Repository layout

| Path | Contents |
|---|---|
| `Mage/` | Rules engine, game state, player API |
| `Mage.Common/` | Remote interfaces, shared views, version handling |
| `Mage.Server/` | Server, managers, RPC implementation, configuration |
| `Mage.Sets/` | Card implementations |
| `Mage.Client/` | Swing desktop client |
| `Mage.WebProxy/` | Jetty proxy and React frontend |
| `Mage.Server.Plugins/` | Player, game, tournament, and deck plugins |
| `Mage.Server.Console/` | Console UI and headless bot runner |
| `Mage.Tests/` | JUnit suites |
| `Mage.Verify/` | Card-data verification |
| `scripts/` | Build, deploy, launch, and verification scripts |

The web frontend source is in `Mage.WebProxy/web/frontend/react`; the served `index.html` and `assets/` are build outputs.

## Workflow

1. Fork the repository and create a branch from `master`. Use a descriptive topic prefix, for example `llm-mana-plan` or `fix-sideboard-race`.
2. Keep the branch focused. One behavior change per pull request is easier to review and easier to rebase across an upstream merge.
3. Build the modules you touch and run the narrowest relevant test while iterating.
4. Run the broader suite before opening a pull request.
5. Rebase on the current `master` rather than merging it into the branch, unless the merge is needed to resolve a conflict.
6. Open a pull request with the behavior, the reason, and the test evidence.

Upstream syncs are a regular part of the work. When a change touches an inherited XMage file, keep the fork's addition separable: a new method, a guarded branch, or an additive field is easier to preserve than an edit in the middle of upstream logic. If an upstream change conflicts with a fork patch, prefer adapting the fork patch over rewriting the upstream code.

## Fail-fast coding style

The project rejects silent fallbacks. If a required value is missing, a call fails, or a configuration is invalid, the code must stop with a clear error at the point of failure. A fallback hides the defect and moves the symptom somewhere harder to diagnose.

Rules:

- Do not invent a default when a configuration value, file, path, or endpoint is missing.
- Do not catch an exception and continue with degraded behavior.
- Do not keep an old code path alive only for compatibility.
- Do not add a `try`/`catch` that swallows the error and returns a plausible value.
- Validate inputs at the boundary and throw with the value, the expected shape, and the consequence.

```java
// Bad: the caller cannot tell that the send failed.
try {
    result = session.sendPlayerString(gameId, choice);
} catch (Exception e) {
    result = false;
}

// Good: the failure is visible and carries context.
assert session != null : "sendPlayerString called with null session";
boolean result = session.sendPlayerString(gameId, choice);
if (!result) {
    throw new IllegalStateException("sendPlayerString failed for choice: " + choice);
}
```

A staged migration may need a temporary shim. Mark it explicitly and give it an expiry condition:

```java
// TODO(shim): expires=issue:mana-plan-control Delete once all callers use the new API.
```

An unlabeled compatibility wrapper is treated as a defect.

## Tests

Every Java behavior change needs a test in `Mage.Tests` or `Mage.WebProxy/src/test`. Choose the smallest layer that can prove the behavior:

- Rules and card interactions: `CardTestPlayerBase`.
- LLM decisions: `CardTestPlayerBaseLLM` with a scripted client.
- Prompt, config, and parser logic: plain JUnit.
- Proxy serializers and gates: plain JUnit in the proxy module.
- Wire or browser paths: the relevant `scripts/verify-*` harness.

Tests must be deterministic and must not require a live model. A test that contacts a provider belongs in the gated integration class and must be documented as such. Run the test once with the fix removed to confirm it fails for the intended reason.

See [Testing](testing.md) for commands and suite layout.

## Commit messages

Use an imperative subject that states the behavior change:

```text
Reject an LLM seat when the server has no LLM capability

The proxy accepted create_table with a COMPUTER_LLM seat against a stock
server and failed later during serialization. Gate the seat and its config
at the message boundary, and cover the rejection in the capability test.
```

The body explains why the change is needed, what the previous behavior was, and any compatibility consequence. Mention a public issue number when one exists. Do not put dates, internal identifiers, or a progress narrative in the message.

For a wire or replay-format change, state it explicitly in the subject or the first body paragraph. A reviewer must not have to infer it from the diff.

## Pull requests

A pull request should include:

- A short description of the problem and the chosen fix.
- The tests that were run, with the exact commands.
- Any change to a pinned class, a protocol message, a configuration key, or a replay format, called out separately.
- A note on upstream conflict risk if the change touches an inherited file.
- Screenshots only for visible UI changes, using generic data.

Keep unrelated formatting and renaming out of a behavioral pull request. A large diff with a small behavioral change is hard to review and hard to rebase.

## Reporting issues

A useful issue includes:

- The LLMage version and the artifact flavor (LLMage, stock, or minimal).
- The operating system and JDK version.
- The client used: desktop, web, or headless.
- Steps to reproduce, with a minimal deck or test fixture when relevant.
- Expected behavior and actual behavior.
- The relevant server or proxy log excerpt, with API keys, hostnames, and personal paths removed.

Do not paste a provider key, a session token, or a full transcript containing private deck data. If the problem depends on a specific model, name the model and the endpoint type, not the credential.

## Review checklist

Before requesting review, confirm:

- The change fails loudly on invalid input and does not add a fallback.
- A test covers the new behavior and fails without the change.
- Core-module edits are additive or narrowly justified.
- No pinned wire class changed without a deliberate pin update and `WireCompatSuidTest`.
- No secret, private host, or personal path appears in code, logs, tests, or docs.
- Configuration keys and defaults are documented where an operator will look for them.
- The build and the relevant test suite pass from a clean tree.

## Related

- [Architecture](architecture.md)
- [Building LLMage](building.md)
- [Testing](testing.md)
- [Wire compatibility](wire-compatibility.md)
- [Extending LLMage](extending.md)
