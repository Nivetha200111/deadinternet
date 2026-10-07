"use strict";
// Dead Internet Lens content script for X and LinkedIn. Collects the open thread, or the posts on a feed, profile or
// search page, as you scroll (both sites virtualize or lazy-load, so it collects incrementally), hosts the overlay
// panel, and decorates replies and posts with classification badges. Reading the page is collector.js's job, shared with the server's live capture.
(() => {
  if (window.__deadInternetLens) return;
  window.__deadInternetLens = true;

  const Collector = window.DeadInternetLensCollector;
  const site = Collector.detect();
  if (!site) return;
  const MAX_REPLIES = 400;
  // The server accepts up to 5000 characters per post; X Premium posts can be much longer.
  const MAX_TEXT = 5000;
  // ------------------------------------------------------------------ shared state
  const PANEL_URL = chrome.runtime.getURL("panel.html");
  const PANEL_ORIGIN = new URL(PANEL_URL).origin;
  const state = {
    // "thread": a single post and its replies. "posts": the posts on a feed, profile or search page.
    mode: null,
    pageKey: null, // thread:<id> or posts:<path>; a change resets what was collected
    threadId: null,
    post: null,
    replies: new Map(), // reply (or post, in posts mode) id -> { id, handle, text, createdAt }
    skipped: new Set(), // replies without text (media-only); they can't be compared
    results: new Map(), // reply id -> badge info, after analysis
    panel: null,
    frame: null,
    panelReady: false,
    collecting: null, // auto-collect timer
    hidden: new Set(), // post types to fold, chosen in the panel
    revealed: new Set(), // folded posts the reader chose to show anyway
  };

  // ------------------------------------------------------------------ reading the thread
  function pageKey() {
    const threadId = site.threadId();
    return threadId ? `thread:${threadId}` : `posts:${location.pathname}`;
  }

  function collect() {
    const key = pageKey();
    if (key !== state.pageKey) reset(key);
    let items;
    if (state.mode === "thread") {
      const { post, replies } = Collector.scan(site);
      // Keep the first good read of the post; it can scroll out of the DOM later.
      if (post && (!state.post || (!state.post.text && post.text))) state.post = post;
      items = replies;
    } else {
      items = Collector.scanPosts(site)?.items ?? [];
    }
    for (const reply of items) {
      if (state.replies.has(reply.id) || state.replies.size >= MAX_REPLIES)
        continue;
      if (!reply.text) {
        state.skipped.add(reply.id);
        continue;
      }
      state.skipped.delete(reply.id);
      state.replies.set(reply.id, {
        id: reply.id,
        handle: reply.handle,
        text: reply.text.slice(0, MAX_TEXT),
        createdAt: reply.createdAt,
      });
    }
    decorate();
    updateLauncher();
    sendCollected();
  }

  function reset(key) {
    stopAutoCollect();
    state.pageKey = key;
    state.mode = key.startsWith("thread:") ? "thread" : "posts";
    state.threadId = state.mode === "thread" ? key.slice("thread:".length) : null;
    state.post = null;
    state.replies.clear();
    state.skipped.clear();
    state.results.clear();
    state.revealed.clear();
    Collector.clearBadges();
    Collector.clearHiding();
    if (state.panel) toPanel({ type: "reset" });
  }

  function conversation() {
    if (state.mode === "posts") return postsConversation();
    const post = state.post;
    return {
      post: {
        id: post.id,
        author: post.handle,
        text: (post.text || "(post without text)").slice(0, MAX_TEXT),
        createdAt: post.createdAt,
      },
      replies: [...state.replies.values()].map((r) => ({
        id: r.id,
        author: { id: r.handle.toLowerCase(), username: r.handle },
        text: r.text,
        createdAt: r.createdAt,
      })),
    };
  }

  // Posts hang off a synthetic root that stands for the page, as in the server's feed capture (CapturedFeed).
  function postsConversation() {
    const posts = [...state.replies.values()];
    const oldest = Math.min(...posts.map((p) => Date.parse(p.createdAt)));
    const accounts = new Set(posts.map((p) => p.handle.toLowerCase())).size;
    const where = /^\/(home|feed(\/[a-z-]+)?)?\/?$/.test(location.pathname)
      ? `your ${site.name} feed`
      : `${site.name} ${location.pathname}`;
    return {
      kind: "feed",
      post: {
        id: `feed-${Math.floor(Date.now() / 1000)}`,
        author: where.slice(0, 100),
        text: `Posts on ${where}: ${posts.length} posts from ${accounts} accounts, collected ${new Date().toISOString().slice(0, 16).replace("T", " ")} UTC`,
        createdAt: new Date(oldest - 1000).toISOString(),
      },
      replies: posts.map((p) => ({
        id: p.id,
        author: { id: p.handle.toLowerCase(), username: p.handle },
        text: p.text,
        createdAt: p.createdAt,
      })),
    };
  }

  let scheduled = false;
  new MutationObserver(() => {
    if (scheduled) return;
    scheduled = true;
    setTimeout(() => {
      scheduled = false;
      collect();
    }, 300);
  }).observe(document.body, { childList: true, subtree: true });
  // Both sites are single-page apps; also notice navigation that doesn't mutate the thread.
  setInterval(() => {
    if (pageKey() !== state.pageKey) collect();
  }, 800);

  // ------------------------------------------------------------------ auto-collect
  // Scrolls and opens "more replies/comments" sections, which is where low-quality replies are often hidden.
  function startAutoCollect() {
    if (state.collecting) return;
    let idle = 0,
      last = -1;
    state.collecting = setInterval(() => {
      if (state.mode === "thread") Collector.clickMore(site);
      Collector.scrollDown();
      collect();
      idle = state.replies.size === last ? idle + 1 : 0;
      last = state.replies.size;
      if (idle >= 5 || state.replies.size >= MAX_REPLIES) stopAutoCollect();
    }, site.autoCollectMs);
    sendCollected();
  }

  function stopAutoCollect() {
    if (!state.collecting) return;
    clearInterval(state.collecting);
    state.collecting = null;
    sendCollected();
  }

  // ------------------------------------------------------------------ badges on the page
  function decorate() {
    Collector.decorate(state.results, (info) => {
      openPanel();
      toPanel({ type: "inspect", username: info.username });
    }, site);
    // Both sites re-render posts as you scroll, which drops the fold; put it back.
    applyHiding();
  }

  function applyHiding() {
    Collector.applyHiding(state.results, state.hidden, state.revealed, (id) => {
      state.revealed.add(id);
      applyHiding();
      sendHidden();
    });
    sendHidden();
  }

  /** Tells the panel how many posts on this page are folded right now. */
  function sendHidden() {
    toPanel({ type: "hiddenCount", count: document.querySelectorAll(".dil-collapsed").length });
  }

  function scrollToReplies(replyIds) {
    if (!Collector.scrollToReplies(replyIds)) toPanel({ type: "notOnPage" });
  }

  // ------------------------------------------------------------------ launcher and panel
  const launcher = document.createElement("button");
  launcher.className = "dil-launcher";
  launcher.hidden = true;
  launcher.innerHTML = "<i></i><span></span>";
  launcher.addEventListener("click", (e) => {
    // A drag ends in a click; only a click that didn't move opens the panel.
    if (launcher.dataset.dragged) {
      delete launcher.dataset.dragged;
      e.preventDefault();
      return;
    }
    openPanel();
  });
  document.body.appendChild(launcher);
  makeDraggable();

  /** The minimized icon can be dragged anywhere; the panel remembers where. */
  function makeDraggable() {
    let start = null;
    launcher.addEventListener("pointerdown", (e) => {
      const r = launcher.getBoundingClientRect();
      start = { x: e.clientX, y: e.clientY, left: r.left, top: r.top, moved: false };
      launcher.setPointerCapture(e.pointerId);
    });
    launcher.addEventListener("pointermove", (e) => {
      if (!start) return;
      const dx = e.clientX - start.x;
      const dy = e.clientY - start.y;
      if (!start.moved && Math.hypot(dx, dy) < 5) return;
      start.moved = true;
      placeLauncher({ left: start.left + dx, top: start.top + dy });
    });
    launcher.addEventListener("pointerup", () => {
      if (start?.moved) {
        launcher.dataset.dragged = "1";
        toPanel({ type: "launcherPos", pos: state.launcherPos });
      }
      start = null;
    });
    addEventListener("resize", () => state.launcherPos && placeLauncher(state.launcherPos));
  }

  function placeLauncher(pos) {
    if (!pos) return;
    const left = Math.max(4, Math.min(innerWidth - launcher.offsetWidth - 4, pos.left));
    const top = Math.max(4, Math.min(innerHeight - launcher.offsetHeight - 4, pos.top));
    state.launcherPos = { left: Math.round(left), top: Math.round(top) };
    Object.assign(launcher.style, { left: `${left}px`, top: `${top}px`, right: "auto", bottom: "auto" });
  }

  function updateLauncher() {
    const n = state.replies.size;
    // On a feed the launcher appears once there is something to analyze; on a thread, right away.
    launcher.hidden =
      (state.mode === "posts" && !n) || Boolean(state.panel && !state.panel.hidden);
    const word =
      state.mode === "posts"
        ? n === 1
          ? "post"
          : "posts"
        : site.name === "LinkedIn"
          ? n === 1
            ? "comment"
            : "comments"
          : n === 1
            ? "reply"
            : "replies";
    launcher.querySelector("span").textContent = String(n);
    launcher.title = `Dead Internet Lens · ${n} ${word}. Click to open, drag to move.`;
    launcher.setAttribute("aria-label", launcher.title);
  }

  // The panel loads hidden with the page, so it can analyze posts as they appear before anyone opens it.
  function createPanel() {
    if (state.panel) return;
    state.panel = document.createElement("div");
    state.panel.className = "dil-panel dil-expanded";
    state.panel.hidden = true;
    state.frame = document.createElement("iframe");
    state.frame.src = PANEL_URL;
    state.frame.title = "Dead Internet Lens overlay";
    state.panel.appendChild(state.frame);
    state.panel.appendChild(dockHandle());
    document.body.appendChild(state.panel);
  }

  function openPanel() {
    createPanel();
    state.panel.hidden = false;
    updateLauncher();
    sendCollected();
    toPanel({ type: "visible", value: true });
  }

  /** Drag the docked overlay's left edge to make it wider or narrower; the panel remembers the width. */
  function dockHandle() {
    const handle = document.createElement("div");
    handle.className = "dil-resize";
    handle.title = "Drag to resize · double-click to reset";
    let start = null;
    handle.addEventListener("pointerdown", (e) => {
      start = { x: e.clientX, width: state.panel.getBoundingClientRect().width };
      handle.setPointerCapture(e.pointerId);
      state.panel.classList.add("dil-resizing");
    });
    handle.addEventListener("pointermove", (e) => {
      if (start) setDockWidth(start.width + (start.x - e.clientX));
    });
    handle.addEventListener("pointerup", () => {
      if (!start) return;
      start = null;
      state.panel.classList.remove("dil-resizing");
      toPanel({ type: "dockWidth", width: state.dockWidth });
    });
    handle.addEventListener("dblclick", () => {
      setDockWidth(null);
      toPanel({ type: "dockWidth", width: null });
    });
    return handle;
  }

  function setDockWidth(px) {
    state.dockWidth = px === null ? null : Math.round(Math.max(360, Math.min(innerWidth - 40, px)));
    if (state.panel) state.panel.style.width = state.dockWidth === null ? "" : state.dockWidth + "px";
  }

  function closePanel() {
    if (state.panel) state.panel.hidden = true;
    toPanel({ type: "visible", value: false });
    updateLauncher();
    launcher.focus();
  }

  function toPanel(message) {
    if (state.frame?.contentWindow && state.panelReady)
      state.frame.contentWindow.postMessage(message, PANEL_ORIGIN);
  }

  function sendCollected() {
    toPanel({
      type: "collected",
      site: site.name,
      mode: state.mode,
      threadId: state.threadId,
      hasPost: state.mode === "posts" || Boolean(state.post),
      count: state.replies.size,
      skipped: state.skipped.size,
      max: MAX_REPLIES,
      collecting: Boolean(state.collecting),
    });
  }

  window.addEventListener("message", (event) => {
    if (
      !state.frame ||
      event.source !== state.frame.contentWindow ||
      event.origin !== PANEL_ORIGIN
    )
      return;
    const msg = event.data || {};
    switch (msg.type) {
      case "ready":
        state.panelReady = true;
        if (msg.dockWidth) setDockWidth(msg.dockWidth);
        if (msg.launcherPos) {
          state.launcherPos = msg.launcherPos;
          // Measured once visible; until then keep the saved spot.
          Object.assign(launcher.style, { left: `${msg.launcherPos.left}px`, top: `${msg.launcherPos.top}px`, right: "auto", bottom: "auto" });
        }
        toPanel({ type: "visible", value: !state.panel.hidden });
        sendCollected();
        break;
      case "requestConversation":
        collect();
        if (state.mode === "posts" && !state.replies.size)
          toPanel({
            type: "error",
            message:
              "No posts with text on this page yet. Scroll the page or use Auto-collect.",
          });
        else if (state.mode === "thread" && !state.post)
          toPanel({
            type: "error",
            message: `Open a single post on ${site.name} so the original post is on the page.`,
          });
        else if (!state.replies.size)
          toPanel({
            type: "error",
            message:
              "Nothing with text collected yet. Scroll the thread or use Auto-collect.",
          });
        else toPanel({ type: "conversation", conversation: conversation() });
        break;
      case "results":
        state.results = new Map(msg.items.map((item) => [item.replyId, item]));
        Collector.clearBadges();
        decorate();
        break;
      case "hide":
        state.hidden = new Set(msg.categories || []);
        applyHiding();
        break;
      case "showAll":
        for (const id of state.results.keys()) state.revealed.add(id);
        applyHiding();
        break;
      case "scrollTo":
        scrollToReplies(msg.replyIds || []);
        break;
      case "autoCollect":
        msg.value ? startAutoCollect() : stopAutoCollect();
        break;
      case "expand":
        state.panel.classList.toggle("dil-expanded", Boolean(msg.value));
        break;
      case "close":
        closePanel();
        break;
    }
  });

  chrome.runtime.onMessage.addListener((msg) => {
    if (msg?.type !== "lens:toggle") return;
    if (state.panel && !state.panel.hidden) closePanel();
    else openPanel();
  });

  collect();
  createPanel();
})();
