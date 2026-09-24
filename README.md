# polyu-agent — PolyU Campus Information Q&A Assistant (Unofficial)

[![English](https://img.shields.io/badge/English-2f81f7?style=flat-square)](README.md)
[![简体中文](https://img.shields.io/badge/简体中文-d0d7de?style=flat-square)](README.zh-CN.md)

[![Deploy](https://github.com/Leewwp/polyu-agent/actions/workflows/deploy.yml/badge.svg)](https://github.com/Leewwp/polyu-agent/actions/workflows/deploy.yml)
[![CI Backend](https://github.com/Leewwp/polyu-agent/actions/workflows/backend.yml/badge.svg)](https://github.com/Leewwp/polyu-agent/actions/workflows/backend.yml)
[![CI Frontend](https://github.com/Leewwp/polyu-agent/actions/workflows/frontend.yml/badge.svg)](https://github.com/Leewwp/polyu-agent/actions/workflows/frontend.yml)
[![License](https://img.shields.io/badge/license-Apache--2.0-blue.svg)](./LICENSE)

A vertical-domain RAG Q&A project built as a second-stage development on top of [nageoffer/ragent](https://github.com/nageoffer/ragent) (Apache-2.0, baseline pinned to tag **1.1.0**, commit `f64de341`). Delivered scenario: **campus information Q&A for The Hong Kong Polytechnic University** — an assistant for academic registration, library, facility booking, student services, scholarships, key dates and deadlines, with citation traceability and scheduled news updates.

![PolyUGuide chat page: agentic Q&A pipeline (question → reasoning → knowledge-base retrieval) and a cited answer with the "N sources" badge](docs/screenshots/chat-answer-zh.png)

## Important disclaimers

- **This is a personal project with no affiliation with The Hong Kong Polytechnic University.** Answers come from public official pages, are for reference only, and the university's official publications always prevail.
- **Official answer data is restricted to public pages that pass the admission rules** (polyu.edu.hk and department sites; robots.txt, access attributes, and terms are checked per host). Content requiring NetID/SSO, authenticated sessions, personalized information, or marked internal/confidential/staff-only never enters the knowledge base; public body text under portal domains is judged by page-level rules.
- Social platforms (Xiaohongshu, Tieba, etc.) are used only as de-identified sources of real demand and for the evaluation set; community answers are not ground truth. Links to community questions may be provided later, kept separate from official answers — posts are never copied.
- This project is a second-stage development on an open-source base, not built from scratch. Base capabilities (hybrid retrieval engine, ingestion pipeline, model routing and fault tolerance, admin console) come from nageoffer/ragent — see the upstream repository for full base documentation. The downstream work concentrates on **business-domain content engineering, retrieval-pipeline localization, and multilingual support**. The upstream `main` branch is evolving toward 2.0; this project does not follow it wholesale (upstream fixes are cherry-picked on demand — see the cherry-pick footnotes in commit history). The lineage stays pinned to the 1.1.0 baseline and retains its Apache-2.0 license ([LICENSE](./LICENSE)).

## The problem it solves

PolyU information is scattered across dozens of department sites (Academic Registry, Student Affairs Office, Pao Yue-kong Library, ITS, …). Students trying to figure out "how do I book the swimming pool", "how does the library printer work", or "how does Add/Drop work" end up digging through multiple documents — or asking experienced seniors on Xiaohongshu. This project aggregates the public information into a **RAG Q&A with citation traceability**:

| Capability | Description |
| --- | --- |
| Domain-partitioned Q&A | knowledge bases partitioned by department/scenario + intent-tree routing (registration / library / facilities / student services / scholarships / exchange …) |
| Citation traceability | inline citation markers + sources panel + official-page preview; the agentic tool block carries a collapsible "N sources" badge (document name + excerpt + jump-to-original) |
| Scheduled refresh | URL-sourced documents refreshed incrementally on cron (native to the base); news feed: scheduled discovery of official/university channels → bilingual AI summaries and classification → feed / trending / topic browsing; global search with sort direction and category scope (feature-flag gated) |
| Multilingual | simplified Chinese and English officially supported at launch; trilingual document identity and cross-lingual retrieval retained underneath; traditional Chinese is compatibility-smoke-tested only for now |
| Feedback & about | anonymous feedback (daily IP limit) with back-office management; about page with markdown editing and a tip jar (feature-flag gated) |
| Real-demand loop | social-media questions feed the golden set and colloquial query forms; failed online questions keep only de-identified scenario + diagnostics and asynchronously produce knowledge-gap reports |

## Interface preview

The live site [polyuguide.com](https://polyuguide.com) can be tried as a guest without registration (daily quota). Click the "N sources" badge in an answer to expand document names, excerpts, and links to the original official pages.

**News feed (Chinese)** — bilingual AI summary cards, category filters, and the daily trending board:

![News feed - Chinese](docs/screenshots/news-feed-zh.png)

**English UI** — one-click language switching:

![News feed - English](docs/screenshots/news-feed-en.png)

**About page** — project statement, unofficial disclaimer, and feedback channel:

![About page](docs/screenshots/about-zh.png)

## Repository layout

- `bootstrap/` — Spring Boot startup module (main configuration, production profile, application assembly)
- `framework/` / `infra-ai/` — base framework layer and AI infrastructure (model routing, middleware adapters, shared plumbing)
- `rag/` — retrieval domain (knowledge bases and ingestion, intent tree, query rewriting, evaluation, news fetching and heat ranking)
- `agent/` — agentic Q&A chain (ReAct, confirmation cards, tracing)
- `mcp-server/` — MCP tool service (sample tools)
- `system/` — users, auth, audit, data retention and other system concerns
- `frontend/` — React frontend (Vite + zustand + Tailwind)
- `resources/` — schema SQL and incremental upgrades, knowledge corpus, demo initializers, local middleware compose
- `deploy/` — production deployment (images, compose orchestration, gateway config, deployment guide)
- `docs/` — base documentation (architecture diagrams, release notes, samples)

## Quick start

Environment and startup follow the upstream documentation and defaults ([nageoffer/ragent](https://github.com/nageoffer/ragent) README, `bootstrap/src/main/resources/application.yaml`). Runtime file storage reuses a private S3-compatible store on the same host (MinIO) instead of a managed object-storage service. Retrieval uses fused pgvector semantic + Elasticsearch keyword channels (ES 9.4.2 + IK, enabled after lexical retrieval passed measured acceptance, with an overall rollback switch kept); Milvus / LightRAG stay off by default. For production deployment (container images, single-host orchestration, deploy pipeline) see [deploy/README.md](./deploy/README.md).

## Current status

The site is live and running (https://polyuguide.com). Main capabilities:

- **Knowledge base**: official-source corpus crawling, parsing, ingestion, and storage/retrieval consistency reconciliation — 280+ official sources in the library (including multilingual versions); chunk sizing frozen by evaluation
- **Retrieval**: fused pgvector + Elasticsearch (IK) dual channel with the rollback switch retained; the evaluation set (human-reviewed core questions + lexical-retrieval challenge questions) is maintained continuously
- **Q&A**: multi-model chat routing (primary + failover + circuit-breaker self-healing), scenario-based bilingual prompts, intent-tree routing, no-answer refusal and stale-citation control
- **News feed**: multi-type fetchers (sitemap / RSS / JSON API / HTML list) + heat model + topic clustering, scheduled incremental updates (feature-flag controlled)
- **Accounts**: email registration/verification with a required unique username, dual-channel login (username or email), self-service account center (change email with re-verification, change password, my-shares management), account deletion (with cooling-off recovery), anonymous-trial quota, public answer sharing (immutable snapshots) — feature-flag controlled per deployment (sharing defaults on, issue #124 unified flag)
- **Site copy**: privacy notice / terms of service / unofficial disclaimer permanently in the footer; the privacy notice discloses third-party model transmission and data-retention periods
- **Mobile**: 375–430px chat main flow usable (sources panel drawer, etc.); full adaptation is a later iteration
- **Security**: bcrypt password hashing with transparent upgrade of legacy entries, server-side role checks on admin endpoints with write-action audit logs, login rate limiting and lockout, automated data-retention cleanup, single-domain CORS allowlist, SSRF guards on uploaded document sources, gateway-layer response-header hardening
- **Engineering**: CI gates (backend quality gate / frontend lint+test+build / dependency patrol / gitleaks full-history secret scanning / CodeQL / image vulnerability scanning / dependency audit) and the production deploy pipeline

## Roadmap

- **Near term**: launch load test; enable anonymous trial and open registration (feature flags)
- **Corpus expansion**: from launch high-value sources toward full-site mechanisms

  | Dimension | Launch target |
  | --- | --- |
  | Knowledge corpus | 100–300 high-value official sources (high-frequency question domains first); full-site expansion is a later mechanism |
  | Intent tree | from 3 domains / 10–15 intents at the evaluation baseline toward 15–25 intents |
  | Evaluation set | 30–60 human-reviewed core questions + ~20 lexical-retrieval challenge questions, later expanding to 80–100 |

- **News feed GA**: scheduled discovery → automatic classification → feed display
- **i18n**: official traditional-Chinese support
- **Stretch**: calendar/deadline MCP tools, a LightRAG graph channel, and — subject to compliance and feedback — evaluating contact with the university
