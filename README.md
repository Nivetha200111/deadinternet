# Dead Internet Lens

An experimental graph visualization of a social-media conversation. It separates **human-like**, **uncertain** and **automation-like** accounts and shows **coordinated clusters** emerging from what first looks like noise.

> Experimental heuristic — not ground truth. Classifications describe behavioral, textual, timing and graph signals. They are never proof of who, or what, is behind an account.

```text
Conversation JSON → Spring Boot → feature extraction → FalkorDB graph → graph features
                → JEV classifier → clusters (algo.WCC) → score → Cytoscape.js visualization
```

## Quick start

Requirements: Java 21+, Docker.

```bash
docker compose up -d
```

```bash
./mvnw spring-boot:run
```

Then open <http://localhost:8080>. The bundled demo is analyzed at startup, so the page opens straight into the reveal.

FalkorDB is published on host port **6380** (browser UI on 3001) so it doesn't collide with another Redis or FalkorDB on 6379. If your Docker install has the standalone binary instead of the plugin, use `docker-compose up -d`.

### Recording a clip

1. Make the browser window the size you want to record (1440×900 or larger works well).
2. Press **F** for cinema mode (hides the source panel and filters, keeping playback controls available), then **Space** to replay the reveal from the start. It takes about 12 seconds:
   - accounts are discovered around the original post
   - replies are compared and similarity edges appear
   - coordination edges pull groups together
   - accounts are classified and drift apart (horizontal regions reflect automation signals; local positions separate graph components)
   - cluster boundaries and the Dead Internet Score appear
3. After the reveal, click a cluster label to isolate it, or use **Find connection** to trace a path between two accounts.

**Re-run analysis** runs the whole pipeline again through FalkorDB. **Replay** only replays the animation.

The standalone app also opens as a glass lens over a read-only view of its analyzed conversation. Close the lens to read the source, then **Activate lens** to bring it back. The extension renders directly over the real host page; it does not substitute a screenshot. All fonts and graph assets are local.

## What you can do

| Interaction | What happens |
| --- | --- |
| Click an account | Inspector with automation/coordination likelihood, confidence, signals, counter-signals, and its FalkorDB neighborhood (coordinated accounts, two-hop reach, most similar replies) |
| Click a cluster label | Zooms to and isolates the cluster: size, averages, reply time span, repeated phrases, member list and density from FalkorDB |
| Filters | All / Human-like / Uncertain / Automation-like / Coordinated; the sidebar counts are clickable too |
| Sensitivity | Moves the classification thresholds over the stored scores (±0.2). Never re-runs the classifier |
| Find connection | Pick two accounts; FalkorDB finds the shortest path through `POSTED`, `SIMILAR_TO` and `COORDINATED_WITH` (the shared original post is excluded) |
| Import | Paste your own conversation JSON |
| Playback | Pause/resume and scrub the 12.4-second reveal without rerunning analysis |
| Explore accounts | Search and inspect accounts with keyboard access; also pick path endpoints |
| Export | Save a PNG of the current network with the classifier source and experimental disclaimer |
| Keyboard | Space replay · Esc clear/close lens · F cinema mode · A anonymize handles |

## Analyze a live X or LinkedIn post

Paste a post URL into the bar at the top of the app and press **Analyze post**:

1. The server opens the post in a real Chrome window that it drives through Playwright, in its own profile separate from your everyday Chrome.
2. It scrolls the thread and opens hidden sections ("Show probable spam" on X, "Load more comments" on LinkedIn). The page streams into the app full-screen, with a live reply counter.
3. When the thread stops growing (or after 400 replies, or 6 minutes), it analyzes what it collected. It badges every reply in the live page, then fades into the graph reveal.

**Stop & analyze** ends scrolling early. **Show browser window** brings the real window forward.

**Logging in:** the first time, X or LinkedIn will ask you to log in. The app pauses and tells you so. Log in yourself in the Lens browser window, then press **Continue**. Lens never sees your password, and the login is remembered in `~/.dead-internet-lens/browser-profile` (change it with `LENS_BROWSER_PROFILE`).

**Before you use it on real accounts:** automated scrolling is against X's and LinkedIn's terms. LinkedIn in particular can restrict accounts that browse automatically, so a secondary account is the safer choice. Capture is deliberately slow (about 1.25 s per scroll step on X and 2.5 s on LinkedIn) and capped at 400 replies.

Settings live under `lens.capture.*` in `application.properties`: browser channel, headless mode, reply cap and time limit.

## Browser overlay for X and LinkedIn

As an alternative to live capture, `extension/` is a Chrome extension that runs the lens on a thread you are already reading in your own browser. It reads the open post and its replies or comments from the page, sends them to your local Lens server, and shows the animated graph in an overlay. Every reply on the page also gets a badge (Human-like / Uncertain / Automation-like, automation %, cluster).

**Install** (with the Lens server running on localhost:8080):

1. Open `chrome://extensions` and turn on **Developer mode**.
2. Click **Load unpacked** and pick the `extension/` folder.
3. Open a single post: `x.com/<user>/status/<id>`, or a LinkedIn post page (`linkedin.com/feed/update/urn:li:activity:<id>/` or `linkedin.com/posts/…`).

**Use:**

1. Scroll the thread, or press **Auto-collect**. It scrolls and opens "Show more replies" / "Show probable spam" on X, or "Load more comments" on LinkedIn. The launcher pill shows how many replies are collected (up to 400).
2. Press **Analyze thread**. The graph builds in the overlay, and badges appear on the replies.
3. Click a badge to inspect that account in the graph. Click a node in the graph to scroll the page to that reply.
4. The lens opens **full-screen by default**. **Dock** brings it back to a side panel; **Expand** restores the full-screen lens. **×** or **Esc** closes it. The original page remains visible through the translucent graph and glass controls.

**What the overlay can and can't see:**

- Neither site shows account age or follower counts in a thread, so classification uses text, timing and graph signals only. The heuristic rescales accordingly, and every account says "account metadata not available".
- LinkedIn only shows relative times ("2h"). Exact times are decoded from post and comment IDs, which embed a millisecond timestamp.
- Media-only replies are skipped, because they can't be compared.
- Real threads usually produce many small clusters. The layout packs them into separate pods, which scale with thread size; the eight largest get full labels, and crowded ones shrink to tags like `C07`. Hover a tag, or any account in that cluster, to see the full label.
- The extension sends the conversation to your local server. If you configure `JEV_URL`, the server forwards structured classification requests to that endpoint.

**Real people:** these are real accounts, and the labels are probabilistic signals, not findings. Before sharing a recording, consider ticking **Anonymize handles in the graph**. It replaces handles with stable pseudonyms (also available as the `A` key or `?anon=1`). Auto-collect clicks and scrolls for you; LinkedIn in particular may flag fast automated browsing, so Auto-collect runs slowly there.

**When a site changes its markup:** all site-specific selectors live in the `X` and `LINKEDIN` adapters at the top of `extension/content.js`. `extension/test-harness/` rebuilds X-like and LinkedIn-like fixture pages from the demo data and serves them with a stub of the extension API, so the whole overlay can be tested in a normal tab:

```bash
python3 extension/test-harness/build_fixture.py
```

```bash
python3 extension/test-harness/serve.py
```

## How it works

### 1. Deterministic features (Java)

- **Text:** NFKC normalization, tokens, lexical diversity, TF-IDF cosine similarity over all reply pairs, near-duplicate detection, and repeated phrases (trigrams shared by ≥3 accounts, stitched into longer phrases). The similarity model sits behind the `SimilarityModel` interface, so embeddings can replace TF-IDF later.
- **Timing:** seconds after the post, similar replies within 30 s and 60 s, burst membership, and the regularity of similar replies. Speed alone is never treated as evidence; timing only matters together with similar text from other accounts.
- **Metadata:** account age, follower/following ratio and posts per day. It is capped at a minority of the heuristic score.
- **Coordination:** for every account pair, the strongest reply pair scores `0.55 × text similarity + 0.30 × timing proximity + 0.15 × shared-phrase overlap`. Pairs at or above 0.75 become `COORDINATED_WITH` edges.

### 2. FalkorDB is part of the pipeline

The graph is written *before* classification, then queried for features:

```text
(Analysis)-[:ANALYZES]->(Post)
(Account)-[:POSTED]->(Reply)-[:REPLIES_TO]->(Post)
(Reply)-[:SIMILAR_TO {similarity, timeDifferenceSeconds}]->(Reply)
(Account)-[:COORDINATED_WITH {score, textSimilarity, timingProximity, sharedPatternScore}]->(Account)
(Reply)-[:CONTAINS_PATTERN]->(Pattern)
(Account)-[:MEMBER_OF]->(Cluster)
```

- `algo.WCC` over `COORDINATED_WITH` gives connected components, which become clusters (≥3 accounts) and feed `clusterSize` to the classifier.
- Neighborhood queries compute each account's degree (node size), coordination partners and component similarity.
- Cluster statistics (averages, reply time span, top phrases) are aggregated with Cypher.
- Inspectors run live traversals: similar replies, two-hop coordination reach, cluster density.
- Path finding uses `algo.SPpaths` with `relDirection: 'both'`, because FalkorDB's `shortestPath()` is directed-only.
- The visualization payload (`/graph`) is read back from FalkorDB rather than from an in-memory copy. Completed analyses survive app restarts.

### 3. JEV classification

JEV is the AI classifier. The app depends on the `JevClassifier` interface; `TypesafeJevClassifier` adapts Typesafe's System One API, while `HttpJevClassifier` retains the custom classification endpoint contract.

| Setting | Env var | Default |
| --- | --- | --- |
| `lens.jev.provider` | `JEV_PROVIDER` | `auto`: Typesafe for `api.typesafe.ai`, custom HTTP for other URLs, otherwise the local heuristic. Explicit options: `typesafe`, `http`, `heuristic` |
| `lens.jev.url` | `JEV_URL` | (empty) |
| `lens.jev.token` | `JEV_TOKEN` | sent as `Authorization: Bearer …` |
| `lens.jev.model` | `JEV_MODEL` | `jev-latest` (Typesafe only) |

For a token created at [Typesafe](https://console.typesafe.ai), set `JEV_URL=https://api.typesafe.ai/v1/systemone` and `JEV_TOKEN` in the environment used to launch the app. Keep the token outside Git. On Windows, user environment variables take effect in newly launched terminals/apps; restart Codex if needed. The app does not automatically read `.env`.

The Typesafe adapter sends one request per account with `model`, the structured account context in `state`, and named `questions`, following the [Typesafe API](https://api.typesafe.ai/redoc). Automation likelihood is the probability of the `automated` choice, confidence is that choice answer's reported confidence, and coordination is a yes/no probability. The automation question treats the text itself as evidence (AI-model phrasing, templated hooks, engagement bait, scam or get-rich claims), because a feed often offers one post per account and no metadata. Separate yes/no questions select evidence labels: AI-written text and spam or engagement bait at probability 0.6 or above, the others at 0.75 or above. Summary text is assembled locally from the returned decisions, not generated by JEV. Existing classification thresholds still determine the displayed label.

For a custom endpoint (`JEV_PROVIDER=http`), JEV receives one `POST` per account, for example:

```json
{
  "account": {"id": "acct_014", "username": "example", "accountAgeDays": 78, "followers": 22, "following": 1804, "totalPosts": 8900},
  "reply": {"text": "Exactly this. People need to wake up.", "secondsAfterParent": 41},
  "features": {"lexicalDiversity": 0.86, "maxTextSimilarity": 0.94, "averageClusterSimilarity": 0.88,
               "similarRepliesWithin30Seconds": 5, "similarRepliesWithin60Seconds": 9, "clusterSize": 11,
               "replyCount": 1, "nearDuplicate": true, "inBurst": true, "timingRegularity": 0.91, "selfSimilarity": 0,
               "followerFollowingRatio": 0.012, "postsPerDay": 114.1, "repeatedPhrases": ["people need to wake up"],
               "repeatedPhraseAccountCount": 9, "coordinatedAccounts": 10, "maxCoordinationScore": 0.93},
  "neighborReplies": ["Exactly this. Wake up people.", "Exactly. People need to wake up."],
  "otherRepliesByAccount": []
}
```

The custom endpoint must return `classification`, `automationLikelihood`, `coordinationLikelihood`, `confidence` (all in [0, 1]), the three signal lists and a `summary`. Responses are validated strictly. Displayed labels always follow the configured thresholds, so the sensitivity control stays consistent.

**When JEV is not configured,** a clearly labeled `LOCAL_HEURISTIC` classifies instead (`HeuristicClassifier`). Every point of its score comes from a named signal shown in the inspector.

**When JEV is configured but fails** for an account (timeout, HTTP error, invalid JSON), that account is marked `uncertain` with `classificationSource = HEURISTIC_FALLBACK`. Confidence is capped at 0.3, and the reason is listed. The analysis never crashes.

### Dead Internet Score

`0.40 × automation-like account share + 0.20 × coordinated account share + 0.25 × near-duplicate reply share + 0.15 × share of accounts in clusters of five or more`. It is uncalibrated, and it is always shown with the disclaimer.

## Deploying

The hosted app has the graph, the inspectors, the demo and **Import**, but not live capture. Capture needs a browser that's logged in as you, so it only exists in the local app (`CAPTURE_ENABLED=false` turns it off, and the UI hides it).

1. **Backend and FalkorDB on Render.** In the Render dashboard, choose New → Blueprint and pick this repository. `render.yaml` creates:
   - the Java service from the `Dockerfile`
   - a private FalkorDB with a 1 GB disk

   The backend comes up at `https://dead-internet-lens.onrender.com`.
2. **Frontend on Vercel.** Import this repository as a Vercel project; `vercel.json` handles the rest. It serves `src/main/resources/static` with no build step and proxies `/api/*` to the Render backend, so the browser only ever talks to one origin. If Render gave the service a different URL, update the rewrite in `vercel.json`.

A managed FalkorDB (for example FalkorDB Cloud) works too. Set `FALKOR_HOST`, `FALKOR_PORT`, `FALKOR_USERNAME` and `FALKOR_PASSWORD` on the backend. Imported analyses are capped at `MAX_STORED_ANALYSES` (default 30); the oldest are deleted first.

## Configuration

All thresholds and weights are in `src/main/resources/application.properties`:

| Property | Default |
| --- | --- |
| `lens.thresholds.human` / `.automation` | 0.40 / 0.65 |
| `lens.similarity.edge-threshold` / `.max-edges-per-reply` | 0.72 / 6 |
| `lens.coordination.threshold` | 0.75 |
| `lens.coordination.text-weight` / `timing-weight` / `pattern-weight` | 0.55 / 0.30 / 0.15 |
| `lens.coordination.timing-window-seconds` | 120 |
| `lens.coordination.min-cluster-size` | 3 |
| `lens.falkor.host` / `.port` (`FALKOR_HOST` / `FALKOR_PORT`) | localhost / 6380 |

## API

| Method | Path | |
| --- | --- | --- |
| `POST` | `/api/analyses` | Start an analysis (202, returns status) |
| `GET` | `/api/analyses/{id}` | Live status and progress counters |
| `GET` | `/api/analyses/{id}/graph` | Accounts, edges, clusters, stats and score (from FalkorDB) |
| `GET` | `/api/analyses/{id}/clusters` | Clusters |
| `GET` | `/api/accounts/{id}?analysis=` | Account plus graph neighborhood |
| `GET` | `/api/clusters/{id}?analysis=` | Cluster members, edges, density |
| `GET` | `/api/path?analysis=&from=&to=` | Shortest behavioral path |
| `GET` | `/api/demo` | Current demo analysis status |
| `POST` | `/api/demo/reset` | Re-run the demo pipeline |
| `GET` | `/api/demo/conversation` | The bundled input JSON |
| `GET` | `/api/health` | Server and FalkorDB status, active classifier |
| `POST` | `/api/captures` | Start a live capture of a post URL (`{"url": …}`) |
| `GET` | `/api/captures/{id}` | Capture progress: stage, replies collected, analysis id |
| `GET` | `/api/captures/{id}/frame` | Latest screenshot of the page being captured |
| `POST` | `/api/captures/{id}/stop` | Stop scrolling and analyze what was collected |
| `POST` | `/api/browser/show` | Bring the capture browser window to the front |

### Input format

```json
{
  "post": {"id": "post_001", "author": "root_user", "text": "AI agents are going to change software development.", "createdAt": "2026-10-06T10:00:00Z"},
  "replies": [
    {"id": "reply_001",
     "author": {"id": "acct_001", "username": "alex_dev", "accountAgeDays": 1800, "followers": 831, "following": 420, "totalPosts": 3900},
     "text": "I think they will change workflows more than programming itself.",
     "createdAt": "2026-10-06T10:02:21Z"}
  ]
}
```

Up to 400 replies are accepted, and an account may reply more than once. Account metadata fields are optional; omit them when you only have handles.

## Demo dataset

`src/main/resources/demo-conversation.json` is **entirely fictional**: 94 replies from 80 accounts. `scripts/generate_demo.py` regenerates it, and its docstring describes each group:

- organic replies
- two near-duplicate template bursts
- accounts repeating their own stock reply
- a group of people coordinating an announcement (coordinated, but human-like)
- generic filler posted far apart (repetitive, but not coordinated)
- accounts that don't fit cleanly

## Tests

```bash
./mvnw test
```

Unit tests cover normalization, similarity, timing, repeated phrases, coordination scoring, thresholds, clustering, the score, JEV response parsing, and JEV fallback (including a real HTTP round trip against a stub server). `FalkorPipelineIntegrationTest` runs the demo through FalkorDB, covering WCC clusters, traversals and `algo.SPpaths`. It is skipped automatically when FalkorDB isn't running.

`LiveCaptureEndToEndTest` drives Chrome (headless) through a full live capture of the X-like and LinkedIn-like fixtures. It's opt-in:

```bash
python3 extension/test-harness/build_fixture.py
```

```bash
./mvnw test -Dtest=LiveCaptureEndToEndTest -Dlens.e2e=true
```

## Project layout

```text
src/main/java/dev/deadinternet/
  config/          LensProperties, FalkorDB driver
  model/           Input records (Post, Reply, Account, Conversation)
  analysis/        Text, timing, phrase, coordination, clustering, score
  classification/  JevClassifier, HTTP JEV client, heuristic, parser, thresholds
  graph/           FalkorGateway, FalkorGraphService (writes), GraphQueryService (reads)
  service/         Pipeline orchestration, progress registry, demo
  capture/         Live capture: browser session, URL validation, scroll-and-collect loop
  controller/      REST API
  dto/             API responses
src/main/resources/static/   index.html, style.css, app.js (vanilla JS + Cytoscape.js)
extension/                   collector.js (X/LinkedIn reader, shared with live capture), Chrome extension, test-harness/
```

## Visual verification

The overlay was checked on the bundled X-style fixture using the real local API: full-screen opening, docking, closing, graph reveal, classification badges, account/cluster inspection, and graph paths. The standalone UI was checked for filtering, sensitivity, anonymization, playback, account search, PNG export, and responsive layouts.
