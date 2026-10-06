"use strict";
// Dead Internet Lens content script for X and LinkedIn. Collects the open thread as you scroll (both sites
// virtualize or lazy-load, so it collects incrementally), hosts the overlay panel, and decorates replies with
// classification badges. Reading the page is collector.js's job, shared with the server's live capture.
(() => {
  if (window.__deadInternetLens) return;
  window.__deadInternetLens = true;

  const Collector = window.DeadInternetLensCollector;
  const site = Collector.detect();
  if (!site) return;
  const MAX_REPLIES = 400;
  // ------------------------------------------------------------------ shared state
  const PANEL_URL = chrome.runtime.getURL("panel.html");
  const PANEL_ORIGIN = new URL(PANEL_URL).origin;
  const state = {
    threadId: null,
    post: null,
    replies: new Map(), // reply id -> { id, handle, text, createdAt }
    skipped: new Set(), // replies without text (media-only); they can't be compared
    results: new Map(), // reply id -> badge info, after analysis
    panel: null,
    frame: null,
    panelReady: false,
    collecting: null, // auto-collect timer
  };

  // ------------------------------------------------------------------ reading the thread
  function collect() {
    const threadId = site.threadId();
    if (threadId !== state.threadId) reset(threadId);
    if (!threadId) return;
    const { post, replies } = Collector.scan(site);
    // Keep the first good read of the post; it can scroll out of the DOM later.
    if (post && (!state.post || (!state.post.text && post.text))) state.post = post;
    for (const reply of replies) {
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
        text: reply.text,
        createdAt: reply.createdAt,
      });
    }
    decorate();
    updateLauncher();
    sendCollected();
  }

  function reset(threadId) {
    stopAutoCollect();
    state.threadId = threadId;
    state.post = null;
    state.replies.clear();
    state.skipped.clear();
    state.results.clear();
    Collector.clearBadges();
    if (state.panel) toPanel({ type: "reset" });
  }

  function conversation() {
    const post = state.post;
    return {
      post: {
        id: post.id,
        author: post.handle,
        text: post.text || "(post without text)",
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
    if (site.threadId() !== state.threadId) collect();
  }, 800);

  // ------------------------------------------------------------------ auto-collect
  // Scrolls and opens "more replies/comments" sections, which is where low-quality replies are often hidden.
  function startAutoCollect() {
    if (state.collecting) return;
    let idle = 0,
      last = -1;
    state.collecting = setInterval(() => {
      Collector.clickMore(site);
      window.scrollBy({ top: window.innerHeight * 0.85, behavior: "smooth" });
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
  }

  function scrollToReplies(replyIds) {
    if (!Collector.scrollToReplies(replyIds)) toPanel({ type: "notOnPage" });
  }

  // ------------------------------------------------------------------ launcher and panel
  const launcher = document.createElement("button");
  launcher.className = "dil-launcher";
  launcher.hidden = true;
  launcher.innerHTML = "<i></i><span></span>";
  launcher.addEventListener("click", openPanel);
  document.body.appendChild(launcher);

  function updateLauncher() {
    launcher.hidden =
      !state.threadId || Boolean(state.panel && !state.panel.hidden);
    const n = state.replies.size;
    const word =
      site.name === "LinkedIn"
        ? n === 1
          ? "comment"
          : "comments"
        : n === 1
          ? "reply"
          : "replies";
    launcher.querySelector("span").textContent = `Lens · ${n} ${word}`;
  }

  function openPanel() {
    if (!state.panel) {
      state.panel = document.createElement("div");
      state.panel.className = "dil-panel dil-expanded";
      state.frame = document.createElement("iframe");
      state.frame.src = PANEL_URL;
      state.frame.title = "Dead Internet Lens overlay";
      state.panel.appendChild(state.frame);
      document.body.appendChild(state.panel);
    }
    state.panel.hidden = false;
    updateLauncher();
    sendCollected();
  }

  function closePanel() {
    if (state.panel) state.panel.hidden = true;
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
      threadId: state.threadId,
      hasPost: Boolean(state.post),
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
        sendCollected();
        break;
      case "requestConversation":
        collect();
        if (!state.post)
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
})();
