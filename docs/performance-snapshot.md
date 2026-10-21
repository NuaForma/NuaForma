# Short processing trials — 2026-10-16

These are small, bounded processing experiments, **not large-scale or sustained load tests**. They give an indication of processing behavior for one synthetic business document. They are not production capacity commitments, hour-long measurements or a guarantee for other templates, platforms or hardware.

## Tested baseline and method

- Private Linux AMD64 candidate built from source commit [`c5d87746e3ef95a80a1950a4b9c05a88ced0521c`](https://github.com/NuaForma/NuaForma/commit/c5d87746e3ef95a80a1950a4b9c05a88ced0521c), including the fixes from [#91](https://github.com/NuaForma/NuaForma/issues/91) / [PR #92](https://github.com/NuaForma/NuaForma/pull/92). It reported the existing `0.2.0-alpha.1` build version; these results must not be attributed to an unchanged published alpha image.
- A six-vCPU, approximately 12-GiB Linux host; container budgets were either two CPU / 3 GiB or five CPU / 8 GiB. CPU model, other deployments and template complexity may materially affect results. The PostgreSQL replica stayed running for the smaller-budget and A/B trials and was temporarily stopped for the larger-budget REST trials; comparisons between those groups are not controlled CPU-only experiments.
- Public synthetic Word example, structured nested data and two-page PDF output. Each REST request had a unique reference. Returned DOCX/PDF files were independently parsed and checked for their own reference and absence of other requests' references. This is a content/structure check, not a new visual-fidelity certification.
- Normal PDF processing retained request-private, network-denied conversion/validation processes, PDFBox and all resource/security controls. Every normal PDF/A result also passed veraPDF. No new public release, reusable validator JVM or persistent validation bypass was introduced for these tests.
- Most REST stages were bounded to roughly 30–60 seconds of issuing requests and finite request counts. Already accepted requests were allowed to finish, so elapsed stage time could be longer. Burst stages deliberately exceeded admission. This is not a steady-state saturation methodology.

## Request counts

| REST group | Submitted requests | Documents returned | Expected busy responses | Unexpected failures |
| --- | ---: | ---: | ---: | ---: |
| 2 CPU / 3 GiB; one or two PDF process slots; HTTP admission 4 | 749 | 717 | 32 | 0 |
| 5 CPU / 8 GiB; two, three or four PDF process slots; HTTP admission 16 | 155 | 155 | 0 | 0 |
| Total of these two completed REST groups | 904 | 872 | 32 | 0 |

The 32 `503 SERVER_BUSY` responses were expected, controlled admission-limit rejections during bursts of eight and sixteen clients against an admission limit of four. They were not successful document generations. No other HTTP failures or output-reference failures occurred in these completed groups.

| Returned document type | 2 CPU / 3 GiB group | 5 CPU / 8 GiB group | Total |
| --- | ---: | ---: | ---: |
| DOCX | 582 | 0 | 582 |
| Standard PDF | 66 | 80 | 146 |
| PDF/A-3b | 69 | 75 | 144 |
| All returned documents | 717 | 155 | 872 |

Both completed REST groups had healthy live/readiness observations throughout: 374 samples in the smaller-budget comparison and 174 in the larger-budget group. No OOM, restart or process-limit event was observed. These counts exclude initial troubleshooting runs, setup/recovery requests and the separate pipeline experiment below; they are not a claim that every earlier exploratory run passed. The pre-fix baseline had a false backend-unavailable response, which led to #91 and is not hidden inside the completed-group totals.

## Concurrency is not throughput

Sixteen concurrent standard-PDF requests and sixteen concurrent PDF/A requests completed successfully with two PDF process slots in the five-CPU group. This proves that four admitted HTTP requests was a configurable limit, not an intrinsic product maximum. It does **not** mean that sixteen renderers were executing at once. Requests can wait for bounded processing slots and take longer under contention.

The following are rates calculated from individual short stages with eight concurrent clients and sixteen documents per stage, not a sustained per-minute or hourly guarantee:

| PDF process slots, same 5 CPU / 8 GiB budget | Standard PDF documents/minute | PDF/A-3b documents/minute | Observed process/thread peak for that configuration |
| --- | ---: | ---: | ---: |
| 2 | 36.59 | 16.66 | 109 / 128 |
| 3 | 34.48 | 16.24 | 115 / 128 |
| 4 | 39.13 | 20.45 | 124 / 128 |

The process peaks cover each configuration's complete run, not necessarily the eight-client stage. Three slots did not outperform two in these short trials; these are not statistically established scaling curves. Four slots left very little headroom below the process/thread limit, so six/eight slots were not tested. The test instance was returned to two PDF slots for margin. Sixteen-client success was tested with two slots, not claimed for the four-slot configuration. Observed peak memory across the larger-budget runs was approximately 1.22 GiB, below the 8-GiB budget.

## Isolating the cost of veraPDF

A separate ABBA experiment alternated two twelve-document batches with veraPDF and two without it: 48 pipeline jobs in total, with two workers, five CPU and an 8-GiB budget. All used the same populated synthetic DOCX bytes and unchanged isolated launcher, font lookup, PDF/A-3b conversion and PDFBox validation. Only the full arm invoked veraPDF and checked its genuine profile/digest attestation. Normal API conformance validation stayed enabled throughout; bypass-arm files were benchmark-only and never served as validated PDF/A results.

| Isolated pipeline arm | Documents | Combined elapsed time | Calculated documents/minute | Median document time |
| --- | ---: | ---: | ---: | ---: |
| With veraPDF | 24 | 111.702 s | 12.89 | 8.222 s |
| Without veraPDF; PDFBox still enabled | 24 | 56.900 s | 25.31 | 4.126 s |

That is approximately **1.96 times the throughput** (+96.4%) and 49.1% less elapsed time in this paired experiment. The median veraPDF stage cost was 4.130 seconds; median conversion was 2.154 seconds with veraPDF in the pipeline and 2.317 without, and PDFBox took 0.880 / 0.914 seconds respectively.

This direct pipeline harness excluded REST/template processing and added a container-execution call per operation. Its absolute rates cannot be compared directly with the REST rates above or applied as a multiplier to other workloads. There were 62 healthy/safe operational samples, no OOM/restart/process-limit events, and two independently parsed sample outputs had the expected two pages and reference. A final ordinary PDF/A API request also passed after the experiment; that verification request is outside the benchmark counts. An initial harness-layout attempt was rejected by the existing sandbox before the measurement; the harness paths were corrected, not the security policy.

## Interpretation and source policy

Conformance validation is a material part of processing cost for this fixture. CPU budget and bounded process concurrency influence throughput; additional admitted HTTP requests mainly increase the amount of work that can wait. Larger memory allowances alone did not explain the observed improvement. Do not extrapolate these short rates into “documents per hour” as measured capacity.

The operator policy in [issue #93](https://github.com/NuaForma/NuaForma/issues/93) is implemented in **unreleased source**: absent `NUAFORMA_PDFA_VALIDATION` or exact `true` keeps validation on; only exact `false` delegates conformance checking to the operator. Invalid values fail startup. Mandatory PDFBox, profile restrictions and isolation remain. Do not configure this variable on a published image and assume it is supported. These historical measurements remain pipeline experiments against the recorded private baseline, not measurements of this new feature implementation.

The next performance qualification should use representative organizational documents, longer repeated runs, warm/cold phases, tail latency, failure/cancellation scenarios and controlled resource settings. These observations alone do not establish a service-level objective, maximum tenant count or production readiness. Sanitized figures may be used on the website only with the method, source baseline and limitations intact; raw logs, credentials, infrastructure addresses and private files do not belong there.
