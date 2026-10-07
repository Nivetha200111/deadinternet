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
  const state = {
    collected: null,
    analysisId: null,
    expanded: true,
    appFrame: null,
  };

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
    $("#analyze").textContent = posts ? "Analyze posts" : "Analyze thread";
    $("#analyze").disabled = !c.hasPost || !c.count;
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
      sendResults(graph);
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
      })),
    );
    toPage({ type: "results", items });
    const counts = { human_like: 0, uncertain: 0, automation_like: 0 };
    graph.accounts.forEach((a) => counts[label(a)]++);
    showMessage(
      `${graph.accounts.length} accounts: ${counts.human_like} human-like, ${counts.uncertain} uncertain, ` +
        `${counts.automation_like} automation-like · ${graph.clusters.length} clusters · score ${Math.round(graph.score.score * 100)}%. ` +
        "Experimental heuristic, not ground truth.",
    );
  }

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
      if (msg.type === "reset") {
        state.appFrame?.remove();
        state.appFrame = null;
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
  toPage({ type: "ready" });
})();
