# Branches, releases and website coordination

## Product repository

`NuaForma/NuaForma` remains one Gradle product repository. Develop is the default working/integration branch. Start issue-linked feature branches there and open their PRs against develop. Both develop and main require verified signed commits, PRs, resolved conversations, `Build (Java 17)` and `Build (Java 25)`. Run relevant native-platform, architecture and behavioral checks too; required build checks are not permission to ignore a failing applicable check.

Main is the reviewed release baseline. Prepare the release/version decision on develop, then promote develop to main through a release PR. The `Release promotion` check accepts only internal develop or deliberately named `hotfix/*` sources. Publication remains separately authorized and protected: run signing/archive/upload/publish workflows explicitly on main. Changing the default branch does not broaden `maven-central` or `ghcr-release` deployment policies, infer publication authority or create tags automatically.

An urgent product hotfix may start from main, enter main through a checked PR and explicit release procedure, then return to develop through a PR. Do not reset/rebase protected branches. Merge commits preserve reviewed signed issue commits. Choose the next version deliberately; a branch merge does not automatically raise minor/patch numbers.

Issue #88 installs the one-time infrastructure migration baseline through develop and a reviewed promotion to main. It changes branch/site ownership, not the released product binaries or their immutable tags. After this synchronization, normal feature work stays on develop until release promotion.

## Own PDF engine integration branch

The original own-engine integration track is complete. PR #120 merged `feature/own-pdf-engine` into develop on 2026-10-26; the feature branch was deleted. Requested watermarks subsequently merged through PR #128. New work starts from and targets **develop**, following the ordinary signed-commit, normal-merge and exact-head check policy.

The selected first native-engine release is **`0.3.0-alpha.1`**, with tag **`v0.3.0-alpha.1`**. It uses the existing protected main-only release procedure. Its images contain no LibreOffice executable or fallback and require no custom host seccomp/AppArmor installation. Verify the exact artifacts, profile/architecture checks, dependencies, filesystem, SBOM, notices and matching source materials before publication. Existing release tags/images stay immutable; prereleases do not move `latest`.

The maintainer authorized preparing this release and its separate website, but publication waits for the maintainer's return. Keep source qualification distinct from published availability. Do not create a branch publisher, bypass protection, infer maintainer audit approval or publish merely because a preparation PR merged.

## Separate website repository

[NuaForma/website](https://github.com/NuaForma/website) owns the static site, downloadable deployment/examples/collections, website authoring/packaging and static QA. Product technical documentation, renderer/runtime security policy, license/source audits and actual runtime qualification remain here. Website inputs do not become part of the Java library graph.

The website's develop branch holds prepared updates and is its default. Main is the live `nuaforma.dev` content and deploys only after Website checks succeed. Ordinary changes use PRs. The maintainer explicitly allows a small exceptional signed direct main push by repository administrators, followed by a PR synchronizing it back to develop. Separate integrity rules keep commit signatures and no-force-push/no-deletion requirements non-bypassable; the exception applies only to review/merge checks in the website repo. It never applies to product main or develop. A failed post-push site check still blocks Pages deployment.

Preserve `https://nuaforma.dev`, its www behavior and all existing paths/download URLs. Do not move kits to repository-prefixed URLs or silently change pinned image versions/security settings. The migration retains original signed history as unchanged ancestry; historical product files may be visible in old website commits, but not its current tree. No private/ignored files are migrated.

The former product Pages deployment may remain as a frozen legacy project URL without the custom domain or a deployment workflow. It is not the source of new website updates; the independent website repository owns the canonical domain.

The website packages reviewed profile/notice inputs from a checksum-bound immutable product source. It does not maintain a second runtime policy. Future updates need the actual reviewed release version/digests and corresponding source/license evidence. Website-only edits require site checks, not a product/renderer build merely because the repository used to be shared.

## Release handoff

1. Prepare product and matching website changes on their respective develop branches; assess docs, examples, platform kits, endpoints and Playground impact.
2. Promote the approved product release to main, then complete its protected signing, exact source/license/security and artifact publication procedures. No product publication occurs on a develop merge.
3. Verify actual public libraries/images. Bind matching website versions/digests, retain reviewed profile snapshots and notices, and repackage affected kits.
4. Promote the website update to its main, wait for its checks/Pages deployment, and verify public HTTPS content and download hashes. A product release is not fully handed off before this succeeds.
5. Bring any emergency website/product main corrections back to their own develop branch through checked PRs.

Do not automatically merge cross-repository changes, publish an unreleased capability or use the website emergency exception for routine work. Link the corresponding PRs and record any explicitly waived/retained qualification scope honestly.

## Real document/client qualification

Keep the product tools `tools/site-qualification/verify_example_kit.py` and `verify_client_collections.py` behind an explicit `--website-directory <website-checkout>` argument. They reuse this product's real isolated container qualification helpers and security policy; static website tooling has no hidden product checkout dependency. An optional staged `--kit-directory` remains available for document examples. Use the exact released image and fresh ignored output directories. These tools generate documents and run clients: execute only when the task calls for that verification, not for repository migration alone.
