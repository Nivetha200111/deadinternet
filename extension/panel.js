"use strict";
// Overlay panel (an extension page framed into x.com). Talks to the content script through the parent window,
// to the local Lens server over HTTP, and to the embedded Lens graph through postMessage.
(() => {
  // A localhost server override is accepted for development (e.g. a different port); nothing else.
  const override = new URLSearchParams(location.search).get("server");
  const SERVER =
    override && /^http:\/\/(localhost|127\.0\.0\.1):\d+$/.test(override)
      ? override
      : "http://localhost:8080";
  const $ = (s) => document.querySelector(s);
  const $$ = (s) => [...document.querySelectorAll(s)];
  const state = {
    collected: null,
    analysisId: null,
    expanded: true,
    appFrame: null,
    graph: null, // the last analysis, for the clean-feed counts
    analyzedCount: null, // posts collected when it ran, so the button can offer an update
    hiddenOnPage: 0,
  };

  // Post types the reader can fold away. Personal posts, mixed signals and too-little-text posts are never hidden:
  // they are either fine or can't be judged. Defaults hide only the clear-cut, high-confidence types.
  const HIDEABLE = [
    ["scam", "Scams", true],
    ["engagement_bait", "Engagement bait", true],
    ["follow_farming", "Follower farming", true],
    ["generic_comment", "Generic comments", true],
    ["content_farm", "Content farms", false],
    ["ai_ad", "AI ads", true],
    ["ai_low_value", "Low-value AI", true],
    ["ai_useful", "Useful AI", false],
    ["ai_written", "AI-written", false],
    ["automated", "Other automation", false],
    ["too_little_text", "Too little text", true],
    ["mixed", "Mixed signals", false],
  ];
  // "Meaningful only" keeps personal posts, useful AI posts and mixed signals worth a look; everything else folds.
  const MEANINGFUL_HIDE = HIDEABLE.map(([k]) => k).filter((k) => k !== "mixed" && k !== "ai_useful");
  const LABELS = Object.fromEntries(HIDEABLE.map(([k, label]) => [k, label]));
  // Remembered between sessions; private to this browser.
  const clean = (() => {
    const defaults = { on: true, hide: HIDEABLE.filter(([, , d]) => d).map(([k]) => k) };
    try {
      const saved = JSON.parse(localStorage.getItem("dil-clean") || "null");
      return saved && Array.isArray(saved.hide)
        ? { on: saved.on !== false, hide: saved.hide, chipsCollapsed: saved.chipsCollapsed === true, dockWidth: saved.dockWidth }
        : defaults;
    } catch {
      return defaults;
    }
  })();
  function saveClean() {
    try {
      localStorage.setItem("dil-clean", JSON.stringify(clean));
    } catch {
      /* storage unavailable: choices last for this session */
    }
  }

  // Only ever post to the X page that framed this panel.
  const PAGE_ORIGIN = location.ancestorOrigins?.[0];
  const toPage = (message) => {
    if (PAGE_ORIGIN) window.parent.postMessage(message, PAGE_ORIGIN);
  };

  function showMessage(text, error = false) {
    $("#message").textContent = text;
    $("#message").classList.toggle("error", error);
    $("#message").hidden = !text;
  }

  async function api(path, options) {
    let response;
    try {
      response = await fetch(SERVER + path, options);
    } catch {
      throw new Error(
        `The Lens server isn't reachable at ${SERVER}. Start it: docker-compose up -d, then ./mvnw spring-boot:run`,
      );
    }
    const body = await response.json().catch(() => null);
    if (!response.ok)
      throw new Error(body?.detail || `Request failed (${response.status})`);
    return body;
  }

  async function checkServer() {
    const el = $("#server-state");
    try {
      const health = await api("/api/health");
      el.className = "server " + (health.falkordb ? "ok" : "down");
      el.textContent = health.falkordb
        ? `server ok · ${health.classifier === "JEV" ? "JEV" : "local heuristic"}`
        : "FalkorDB is not running";
    } catch {
      el.className = "server down";
      el.textContent = "server offline";
    }
  }

  function renderCollected(c) {
    state.collected = c;
    const posts = c.mode === "posts";
    $("#count").textContent = c.count;
    const noun = posts
      ? c.count === 1
        ? "post"
        : "posts"
      : c.site === "LinkedIn"
        ? c.count === 1
          ? "comment"
          : "comments"
        : c.count === 1
          ? "reply"
          : "replies";
    $("#count-label").textContent = `${noun} collected`;
    const notes = [];
    if (posts)
      notes.push(
        c.count >= c.max
          ? `Limit of ${c.max} reached.`
          : c.count
            ? "Scroll to load more posts, or open a single post to analyze its replies."
            : `Scroll your ${c.site} feed, a profile or search results to collect posts.`,
      );
    else if (!c.hasPost)
      notes.push("Scroll to the top so the original post is on the page.");
    else
      notes.push(
        c.count >= c.max
          ? `Limit of ${c.max} reached.`
          : "Scroll the thread to load more.",
      );
    if (c.skipped) notes.push(`${c.skipped} media-only skipped.`);
    $("#count-note").textContent = notes.join(" ");
    $("#auto").textContent = c.collecting ? "Stop" : "Auto-collect";
    $("#auto").title = posts
      ? "Scroll the page to collect more posts"
      : "Scroll the thread and open hidden reply sections";
    $("#auto").classList.toggle("active", c.collecting);
    $("#auto").disabled = !c.mode;
    const fresh = state.analyzedCount != null ? c.count - state.analyzedCount : 0;
    $("#analyze").textContent =
      fresh > 0 ? `Update (+${fresh} new)` : posts ? "Analyze posts" : "Analyze thread";
    $("#analyze").title =
      fresh > 0 ? `${fresh} more ${noun} loaded since the last analysis` : "";
    $("#analyze").disabled = !c.hasPost || !c.count;
    $("#clean-title").textContent = posts ? "Clean my feed" : "Clean this thread";
  }

  async function analyze(conversation) {
    showMessage("");
    $("#analyze").disabled = true;
    try {
      const status = await api("/api/analyses", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(conversation),
      });
      state.analysisId = status.id;
      mountGraph(status.id);
      let current = status;
      while (current.status === "RUNNING") {
        await new Promise((r) => setTimeout(r, 400));
        current = await api(`/api/analyses/${status.id}`);
      }
      if (current.status !== "COMPLETE")
        throw new Error(current.error || "Analysis failed");
      const graph = await api(`/api/analyses/${status.id}/graph`);
      state.analyzedCount = conversation.replies.length;
      sendResults(graph);
      if (state.collected) renderCollected(state.collected);
    } catch (e) {
      showMessage(e.message, true);
    } finally {
      $("#analyze").disabled = false;
    }
  }

  function mountGraph(id) {
    $("#placeholder").hidden = true;
    document.body.classList.add("has-graph");
    state.appFrame?.remove();
    const frame = document.createElement("iframe");
    frame.src = `${SERVER}/?analysis=${encodeURIComponent(id)}&embed=1${$("#anon").checked ? "&anon=1" : ""}`;
    frame.title = "Dead Internet Lens graph";
    frame.addEventListener("load", sendHide);
    $("#stage").appendChild(frame);
    state.appFrame = frame;
  }

  // Badges on the page use the server's default thresholds; the sensitivity slider stays inside the graph.
  function sendResults(graph) {
    const { human, automation } = graph.thresholds;
    const label = (a) =>
      a.classificationSource === "HEURISTIC_FALLBACK"
        ? "uncertain"
        : a.automationLikelihood < human
          ? "human_like"
          : a.automationLikelihood < automation
            ? "uncertain"
            : "automation_like";
    const clusterIndex = new Map(graph.clusters.map((c) => [c.id, c.index]));
    const items = graph.accounts.flatMap((a) =>
      a.replies.map((r) => ({
        replyId: r.id,
        username: a.username,
        classification: label(a),
        automation: a.automationLikelihood,
        coordination: a.coordinationLikelihood,
        cluster: a.clusterId ? clusterIndex.get(a.clusterId) : null,
        category: a.category,
        categoryLabel: LABELS[a.category] || null,
      })),
    );
    toPage({ type: "results", items });
    state.graph = graph;
    renderClean();
    sendHide();
    const counts = { human_like: 0, uncertain: 0, automation_like: 0 };
    graph.accounts.forEach((a) => counts[label(a)]++);
    showMessage(
      `${graph.accounts.length} accounts: ${counts.human_like} human-like, ${counts.uncertain} uncertain, ` +
        `${counts.automation_like} automation-like · ${graph.clusters.length} clusters · score ${Math.round(graph.score.score * 100)}%. ` +
        "Experimental heuristic, not ground truth.",
    );
  }

  // ---------------------------------------------------------------- clean my feed
  const hiding = () => (clean.on ? clean.hide : []);

  function sendHide() {
    toPage({ type: "hide", categories: hiding() });
    state.appFrame?.contentWindow?.postMessage({ type: "lens:hide", categories: hiding() }, SERVER);
  }

  function renderClean() {
    const g = state.graph;
    $("#clean").hidden = !g;
    if (!g) return;
    const posts = new Map();
    let total = 0;
    for (const a of g.accounts) {
      total += a.replies.length;
      if (a.category) posts.set(a.category, (posts.get(a.category) || 0) + a.replies.length);
    }
    $("#clean-on").checked = clean.on;
    $("#clean").classList.toggle("off", !clean.on);
    $("#clean-chips").innerHTML = HIDEABLE.map(
      ([k, label]) =>
        `<button type="button" data-cat="${k}" aria-pressed="${clean.hide.includes(k)}" title="${clean.hide.includes(k) ? "Hidden. Click to show these posts." : "Shown. Click to hide these posts."}">${label}<b>${posts.get(k) || 0}</b></button>`,
    ).join("");
    $$("#clean-chips [data-cat]").forEach(
      (b) =>
        (b.onclick = () => {
          const k = b.dataset.cat;
          clean.hide = clean.hide.includes(k) ? clean.hide.filter((x) => x !== k) : [...clean.hide, k];
          clean.on = true;
          saveClean();
          renderClean();
          sendHide();
        }),
    );
    const hidden = clean.on ? clean.hide.reduce((sum, k) => sum + (posts.get(k) || 0), 0) : 0;
    const noun = state.collected?.mode === "posts" ? "posts" : "replies";
    $("#clean-summary").innerHTML = !clean.on
      ? `Showing everything`
      : hidden
        ? `Hiding <b>${hidden}</b> of ${total} ${noun}<button type="button" id="show-all">Show all</button>`
        : `Nothing to hide here`;
    $("#clean-meaningful").setAttribute(
      "aria-pressed",
      String(clean.on && MEANINGFUL_HIDE.every((k) => clean.hide.includes(k))),
    );
    const showAll = $("#show-all");
    if (showAll) showAll.onclick = () => toPage({ type: "showAll" });
  }

  // The chips can fold away to give the graph more room; remembered like the other choices.
  function setChipsCollapsed(collapsed) {
    $("#clean").classList.toggle("chips-collapsed", collapsed);
    $("#clean-toggle").setAttribute("aria-expanded", String(!collapsed));
    $("#clean-toggle").title = collapsed ? "Show the post-type chips" : "Hide the post-type chips";
  }
  setChipsCollapsed(clean.chipsCollapsed === true);
  $("#clean-toggle").onclick = () => {
    clean.chipsCollapsed = !clean.chipsCollapsed;
    saveClean();
    setChipsCollapsed(clean.chipsCollapsed);
  };

  $("#clean-meaningful").onclick = () => {
    clean.hide = [...new Set([...clean.hide, ...MEANINGFUL_HIDE])];
    clean.on = true;
    saveClean();
    renderClean();
    sendHide();
  };

  $("#clean-on").onchange = () => {
    clean.on = $("#clean-on").checked;
    saveClean();
    renderClean();
    sendHide();
  };

  window.addEventListener("message", (event) => {
    const msg = event.data || {};
    if (event.source === window.parent) {
      if (msg.type === "collected") renderCollected(msg);
      if (msg.type === "conversation") analyze(msg.conversation);
      if (msg.type === "error") showMessage(msg.message, true);
      if (msg.type === "notOnPage")
        showMessage(
          `That ${state.collected?.mode === "posts" ? "post" : "reply"} isn't loaded on the page right now. Scroll to bring it back.`,
        );
      if (msg.type === "inspect" && state.appFrame)
        state.appFrame.contentWindow.postMessage(
          { type: "lens:inspect", username: msg.username },
          SERVER,
        );
      if (msg.type === "hiddenCount") state.hiddenOnPage = msg.count;
      // The page can't keep the docked width itself (that would write to the site's storage); the panel does.
      if (msg.type === "dockWidth") {
        clean.dockWidth = msg.width;
        saveClean();
      }
      if (msg.type === "reset") {
        state.appFrame?.remove();
        state.appFrame = null;
        state.graph = null;
        state.analyzedCount = null;
        renderClean();
        $("#placeholder").hidden = false;
        document.body.classList.remove("has-graph");
        showMessage("");
      }
      return;
    }
    if (
      state.appFrame &&
      event.source === state.appFrame.contentWindow &&
      event.origin === SERVER
    ) {
      if (msg.type === "lens:close") toPage({ type: "close" });
      if (msg.type === "lens:account")
        toPage({ type: "scrollTo", replyIds: msg.replyIds });
    }
  });

  $("#analyze").onclick = () => toPage({ type: "requestConversation" });
  $("#auto").onclick = () =>
    toPage({ type: "autoCollect", value: !state.collected?.collecting });
  $("#close").onclick = () => toPage({ type: "close" });
  $("#expand").textContent = "Dock";
  $("#expand").onclick = () => {
    state.expanded = !state.expanded;
    $("#expand").textContent = state.expanded ? "Dock" : "Expand";
    toPage({ type: "expand", value: state.expanded });
  };
  $("#anon").onchange = () =>
    state.appFrame?.contentWindow.postMessage(
      { type: "lens:anon", value: $("#anon").checked },
      SERVER,
    );

  document.addEventListener("keydown", (e) => {
    if (e.key === "Escape") toPage({ type: "close" });
  });
  checkServer();
  setInterval(checkServer, 15000);
  toPage({ type: "ready", dockWidth: clean.dockWidth ?? null });
})();
