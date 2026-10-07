"use strict";
// Dead Internet Lens thread reader for X and LinkedIn, shared by the browser extension (content.js) and the Lens
// server's live capture, which injects this file into the browser it drives. Everything site-specific lives here.
(() => {
  if (window.DeadInternetLensCollector) return;

  const LABEL = {
    human_like: "Human-like",
    uncertain: "Uncertain",
    automation_like: "Automation-like",
  };
  const ownedBy = (el, container, selector) =>
    el.closest(selector) === container;

  // ------------------------------------------------------------------ site adapters
  // scan() returns { post, replies } where each item is { el, id, handle, text, createdAt (ISO) }.

  const X = {
    name: "X",
    hosts: /(^|\.)(x|twitter)\.com$/,
    autoCollectMs: 1200,
    moreButtons:
      /^(show (more|additional) replies|show probable spam|show spam)/i,
    threadId: () =>
      location.pathname.match(/^\/[^/]+\/status\/(\d+)/)?.[1] ?? null,
    isFeed: () => /^\/(home)?\/?$/.test(location.pathname),
    readTweet(el) {
      // A tweet's own timestamp links to its status URL; promoted tweets have none.
      const time = [...el.querySelectorAll('a[href*="/status/"] time')].find(
        (t) => ownedBy(t, el, "article"),
      );
      const match = time
        ?.closest("a")
        .getAttribute("href")
        .match(/^\/([^/]+)\/status\/(\d+)/);
      if (!match) return null;
      // Skip quoted tweets' text, which sits inside a role="link" card.
      const textEl = [...el.querySelectorAll('[data-testid="tweetText"]')].find(
        (t) => ownedBy(t, el, "article") && !t.closest('div[role="link"]'),
      );
      return {
        el,
        id: match[2],
        handle: match[1],
        text: textEl ? textEl.innerText.trim() : "",
        createdAt: time.getAttribute("datetime"),
      };
    },
    scanFeed() {
      return [...document.querySelectorAll('article[data-testid="tweet"]')]
        .map((el) => X.readTweet(el))
        .filter(Boolean);
    },
    scan(threadId) {
      let post = null;
      const items = [];
      for (const el of document.querySelectorAll(
        'article[data-testid="tweet"]',
      )) {
        const item = X.readTweet(el);
        if (!item) continue;
        if (item.id === threadId) post = item;
        else items.push(item);
      }
      // Ancestors of the focal post (shown above it) are older than it; replies are newer.
      return {
        post,
        replies: post ? items.filter((i) => i.createdAt >= post.createdAt) : [],
      };
    },
    badgeAnchor: (el) => el.querySelector('[data-testid="User-Name"]'),
  };

  const LINKEDIN = {
    name: "LinkedIn",
    hosts: /(^|\.)linkedin\.com$/,
    // /feed/ in the old layout; /feed/foryou/ (and other tabs) since the 2026 redesign.
    isFeed: () => /^\/feed(\/[a-z-]+)?\/?$/.test(location.pathname),
    scanFeed() {
      const CARD = '[data-urn^="urn:li:activity:"], [data-urn^="urn:li:ugcPost:"]';
      const items = LINKEDIN.scanRedesignedFeed();
      for (const el of document.querySelectorAll(CARD)) {
        // Cards can nest (a repost wraps the original); read only the outermost.
        if (el.parentElement?.closest(CARD)) continue;
        const id = el.getAttribute("data-urn").match(/(\d+)$/)?.[1];
        const author = el.querySelector(
          '.update-components-actor__meta-link, .update-components-actor__container a[href*="/in/"], .update-components-actor__container a[href*="/company/"]',
        );
        const textEl = [
          ...el.querySelectorAll(".update-components-text, .feed-shared-update-v2__description, .feed-shared-inline-show-more-text"),
        ].find((t) => !t.closest('[data-id^="urn:li:comment:"]'));
        const handle = linkedInHandle(author?.getAttribute("href"));
        // Promoted cards carry no activity id or no author profile link.
        if (!id || !handle) continue;
        items.push({ el, id, handle, text: textEl ? textEl.innerText.trim() : "", createdAt: linkedInTime(id, null) });
      }
      return items;
    },
    /**
     * The redesigned feed has no activity ids, timestamps or stable class names. Each post is a list item whose
     * componentkey carries an opaque per-post key, and its text is the first expandable-text-box.
     */
    scanRedesignedFeed() {
      const items = [];
      for (const el of document.querySelectorAll(REDESIGNED_CARD)) {
        const key = el.getAttribute("componentkey").slice("update-card-focus".length).replace(/[^\w-]/g, "");
        const header = redesignedHeader(el);
        if (!key || header.promoted) continue;
        const handle = linkedInHandle(header.author?.getAttribute("href"));
        if (!handle) continue;
        const textEl = el.querySelector('[data-testid="expandable-text-box"]');
        items.push({ el, id: `li-${key}`.slice(0, 100), handle, text: textEl ? textEl.innerText.trim() : "", createdAt: linkedInTime(null, header.relativeTime) });
      }
      return items;
    },
    autoCollectMs: 2500, // slower: LinkedIn is quick to flag automated browsing
    moreButtons:
      /^(load more comments|show more comments|see more comments|load more replies|see previous replies|load previous replies|show previous replies)/i,
    threadId: () =>
      location.pathname.match(/urn:li:(?:activity|ugcPost|share):(\d+)/)?.[1] ??
      location.pathname.match(
        /^\/posts\/.*-(?:activity|ugcPost|share)-(\d+)/,
      )?.[1] ??
      null,
    scan(threadId) {
      const postEl =
        document.querySelector(`[data-urn$=":${threadId}"]`) ||
        document.querySelector(
          '[data-urn^="urn:li:activity:"], [data-urn^="urn:li:ugcPost:"]',
        ) ||
        document.querySelector(".feed-shared-update-v2");
      if (!postEl) return { post: null, replies: [] };
      const COMMENT = '[data-id^="urn:li:comment:"]';
      const postAuthor = postEl.querySelector(
        '.update-components-actor__meta-link, .update-components-actor__container a[href*="/in/"], a[href*="/in/"], a[href*="/company/"]',
      );
      const postText = [
        ...postEl.querySelectorAll(
          ".update-components-text, .feed-shared-update-v2__description, .feed-shared-inline-show-more-text",
        ),
      ].find((t) => !t.closest(COMMENT));
      const post = {
        el: postEl,
        id: threadId,
        handle: linkedInHandle(postAuthor?.getAttribute("href")) || "author",
        text: postText ? postText.innerText.trim() : "",
        createdAt: linkedInTime(threadId, null),
      };
      const replies = [];
      for (const el of document.querySelectorAll(COMMENT)) {
        const id = el.getAttribute("data-id").match(/(\d+)\)?$/)?.[1];
        const author = [
          ...el.querySelectorAll('a[href*="/in/"], a[href*="/company/"]'),
        ].find((a) => ownedBy(a, el, COMMENT));
        const textEl = [
          ...el.querySelectorAll(
            '.comments-comment-item__main-content, .comments-comment-entity__content .update-components-text, .update-components-text, [class*="comment-item__main-content"]',
          ),
        ].find((t) => ownedBy(t, el, COMMENT));
        const handle = linkedInHandle(author?.getAttribute("href"));
        if (!id || !handle) continue;
        const relative = [
          ...el.querySelectorAll("time, .comments-comment-meta__data"),
        ].find((t) => ownedBy(t, el, COMMENT))?.textContent;
        // Coarse relative times ("2h") can land before the post; clamp so the reply still follows it.
        const createdAt = new Date(
          Math.max(
            Date.parse(linkedInTime(id, relative)),
            Date.parse(post.createdAt),
          ),
        ).toISOString();
        replies.push({
          el,
          id,
          handle,
          text: textEl ? textEl.innerText.trim() : "",
          createdAt,
        });
      }
      return { post, replies };
    },
    badgeAnchor: (el) => {
      const COMMENT = '[data-id^="urn:li:comment:"]';
      if (el.matches(REDESIGNED_CARD)) {
        // The redesign stacks the header's children in one grid cell, so a badge appended there lands on top of the
        // name. Put it inline, right after the name text.
        const author = redesignedHeader(el).author;
        const name = author && [...author.querySelectorAll("span, p, div")].find(
          (e) => e.childElementCount === 0 && e.textContent.trim(),
        );
        return name ? { after: name } : author ? { after: author } : null;
      }
      if (!el.matches(COMMENT)) {
        return el.querySelector(".update-components-actor__meta, .update-components-actor__title, .update-components-actor__container");
      }
      return [
        ...el.querySelectorAll(
          ".comments-comment-meta__description-container, .comments-post-meta__name, .comments-comment-meta__description-title, .comments-comment-meta__description",
        ),
      ].find((a) => ownedBy(a, el, COMMENT));
    },
  };

  const REDESIGNED_CARD = '[role="listitem"][componentkey^="update-card-focus"]';
  const SOCIAL_CONTEXT = /(likes?|loves?|celebrates?|supports?|finds this|commented on|reposted|follows?) (this|that)?/i;

  /**
   * Reads the header above a redesigned card's text: the author is the first named profile link, after any
   * "X likes this" / "X reposted this" line naming someone else.
   */
  function redesignedHeader(card) {
    const text = card.querySelector('[data-testid="expandable-text-box"]');
    const above = (node) => !text || Boolean(node.compareDocumentPosition(text) & Node.DOCUMENT_POSITION_FOLLOWING);
    const leaves = [...card.querySelectorAll("span, p, div, a")].filter(
      (e) => e.childElementCount === 0 && above(e) && e.textContent.trim(),
    );
    const context = leaves.find((e) => SOCIAL_CONTEXT.test(e.textContent) && e.textContent.trim().length < 120);
    const author = [...card.querySelectorAll('a[href*="/in/"], a[href*="/company/"]')].find(
      (a) =>
        above(a) &&
        a.innerText.trim() &&
        !(context && (a.contains(context) || context.compareDocumentPosition(a) & Node.DOCUMENT_POSITION_PRECEDING)),
    );
    return {
      author,
      promoted: leaves.some((e) => /^(Promoted|Sponsored)$/i.test(e.textContent.trim())),
      relativeTime: leaves.map((e) => e.textContent.trim()).find((t) => /^\d+\s*(s|m|h|d|w|mo|yr)/i.test(t)) ?? null,
    };
  }

  /** /in/jane-doe-1a2b/ → jane-doe-1a2b; /company/acme/ → company-acme */
  function linkedInHandle(href) {
    if (!href) return null;
    const person = href.match(/\/in\/([^/?#]+)/);
    if (person) return decodeURIComponent(person[1]);
    const company = href.match(/\/company\/([^/?#]+)/);
    return company ? "company-" + decodeURIComponent(company[1]) : null;
  }

  /**
   * LinkedIn post and comment IDs carry their creation time in the top 41 bits (milliseconds since the epoch).
   * Falls back to the relative label ("3h", "2d", "1w", "4mo", "1yr"), which is only approximate.
   */
  function linkedInTime(id, relative) {
    if (id) try {
      const ms = Number(BigInt(id) >> 22n);
      if (ms > Date.UTC(2012, 0, 1) && ms < Date.now() + 86400000)
        return new Date(ms).toISOString();
    } catch {
      /* not numeric */
    }
    const m = String(relative || "")
      .trim()
      .match(/^(\d+)\s*(mo|yr|s|m|h|d|w|y)/i);
    const unit = {
      s: 1,
      m: 60,
      h: 3600,
      d: 86400,
      w: 604800,
      mo: 2592000,
      yr: 31536000,
      y: 31536000,
    }[m?.[2].toLowerCase()];
    return new Date(
      Date.now() - (m && unit ? Number(m[1]) * unit * 1000 : 0),
    ).toISOString();
  }

  // Test fixtures can't use the real hostnames; they mark the page instead.
  const ADAPTERS = { x: X, linkedin: LINKEDIN };

  /** The adapter for this page, or null. Test fixtures can't use real hostnames, so they mark the page instead. */
  function detect() {
    return (
      Object.values(ADAPTERS).find((a) => a.hosts.test(location.hostname)) ||
      ADAPTERS[document.documentElement.dataset.dilPlatform] ||
      null
    );
  }

  /**
   * Reads what is on the page right now. Tags each post/reply element with data-dil-item so badges can find it
   * later, and returns plain serializable data.
   */
  function scan(site = detect()) {
    if (!site) return null;
    const threadId = site.threadId();
    if (!threadId) return { site: site.name, threadId: null, post: null, replies: [] };
    const { post, replies } = site.scan(threadId);
    const plain = (item) => ({ id: item.id, handle: item.handle, text: item.text, createdAt: item.createdAt });
    if (post) post.el.dataset.dilItem = post.id;
    replies.forEach((r) => (r.el.dataset.dilItem = r.id));
    return { site: site.name, threadId, post: post && plain(post), replies: replies.map(plain) };
  }

  /** Reads every post currently rendered in a home feed, tagging each element for badges. */
  function scanFeed(site = detect()) {
    if (!site || !site.isFeed()) return null;
    return scanPosts(site);
  }

  /** Like scanFeed, but on any page that lists posts (feed, profile, search), not only the home feed. */
  function scanPosts(site = detect()) {
    if (!site) return null;
    const plain = (item) => ({ id: item.id, handle: item.handle, text: item.text, createdAt: item.createdAt });
    const items = site.scanFeed();
    items.forEach((i) => (i.el.dataset.dilItem = i.id));
    return { site: site.name, items: items.map(plain) };
  }

  /**
   * Scrolls the feed or thread down by most of a screen. LinkedIn's redesign scrolls an inner container (main#workspace)
   * rather than the window, so when the page itself can't scroll this scrolls the largest element that can. The scroll
   * is instant: smooth scrolls get cancelled by the sites' own lazy loading and re-layout, and then never advance.
   */
  function scrollDown() {
    const root = document.scrollingElement || document.documentElement;
    const scrollable = (el) =>
      el.scrollHeight > el.clientHeight + 50 && /(auto|scroll)/.test(getComputedStyle(el).overflowY);
    let target = null;
    if (root.scrollHeight <= root.clientHeight + 50) {
      const start = document.querySelector("[data-dil-item]") || document.querySelector('[data-testid="mainFeed"], main');
      for (let el = start; el && el !== root && !target; el = el.parentElement) if (scrollable(el)) target = el;
      if (!target) {
        target = [...document.querySelectorAll("main, section, div")]
          .filter(scrollable)
          .sort((a, b) => b.scrollHeight - a.scrollHeight)[0] ?? null;
      }
    }
    if (target) target.scrollTop += target.clientHeight * 0.85;
    else window.scrollBy(0, innerHeight * 0.85);
  }

  /** Opens "show more replies" / "load more comments" sections; returns how many it clicked. */
  function clickMore(site = detect()) {
    let clicked = 0;
    for (const button of document.querySelectorAll('button, [role="button"]')) {
      if (site.moreButtons.test(button.textContent.trim())) {
        button.click();
        clicked++;
      }
    }
    return clicked;
  }

  function clearBadges() {
    document.querySelectorAll(".dil-badge").forEach((b) => b.remove());
    document.querySelectorAll('[class*="dil-tinted-"]').forEach((el) =>
      el.classList.remove("dil-tinted-human_like", "dil-tinted-uncertain", "dil-tinted-automation_like"),
    );
  }

  /**
   * Adds a badge to every tagged reply that has a result. results: Map of reply id -> { classification,
   * automation, coordination, cluster, username }. onClick(info) runs when a badge is clicked.
   */
  function decorate(results, onClick, site = detect()) {
    if (!results.size || !site) return;
    for (const el of document.querySelectorAll("[data-dil-item]")) {
      const info = results.get(el.dataset.dilItem);
      if (!info || [...el.querySelectorAll(".dil-badge")].some((b) => b.closest("[data-dil-item]") === el)) continue;
      const anchor = site.badgeAnchor(el);
      if (!anchor) continue;
      const pct = (v) => `${Math.round(v * 100)}%`;
      const badge = document.createElement("span");
      badge.className = `dil-badge dil-${info.classification}`;
      badge.title =
        `${LABEL[info.classification]} signals · ${pct(info.automation)} automation likelihood · ` +
        `${pct(info.coordination)} coordination likelihood. Experimental heuristic, not proof.`;
      badge.innerHTML = `<i class="dil-shape"></i><b></b><span class="dil-pct"></span>${info.cluster ? '<span class="dil-cluster"></span>' : ""}`;
      badge.querySelector("b").textContent = LABEL[info.classification];
      badge.querySelector(".dil-pct").textContent = pct(info.automation);
      if (info.cluster) badge.querySelector(".dil-cluster").textContent = `· C${String(info.cluster).padStart(2, "0")}`;
      if (onClick) {
        badge.addEventListener("click", (e) => {
          e.preventDefault();
          e.stopPropagation();
          onClick(info);
        });
      }
      if (anchor.after instanceof Element) anchor.after.after(badge);
      else anchor.appendChild(badge);
      el.classList.add(`dil-tinted-${info.classification}`);
    }
  }

  /**
   * Folds every analyzed post whose post type is in {@code hidden} into a one-line note, and unfolds the rest. A
   * folded post keeps its place, so the feed doesn't jump; clicking the note shows it again (onReveal(id)).
   * results: Map of item id -> { category, categoryLabel, ... }.
   */
  function applyHiding(results, hidden, revealed, onReveal) {
    for (const el of document.querySelectorAll("[data-dil-item]")) {
      const id = el.dataset.dilItem;
      const info = results.get(id);
      const hide = Boolean(info && info.category && hidden.has(info.category) && !revealed.has(id));
      const stub = [...el.children].find((c) => c.classList.contains("dil-stub"));
      if (hide && !stub) {
        const note = document.createElement("div");
        note.className = "dil-stub";
        note.setAttribute("role", "button");
        note.tabIndex = 0;
        note.title = "Hidden by Dead Internet Lens. Click to show this post.";
        note.innerHTML = '<i></i><span>Hidden by Lens · <b></b></span><em>Show</em>';
        note.querySelector("b").textContent = info.categoryLabel || info.category;
        const reveal = (e) => {
          // The post itself links elsewhere (X opens the tweet); only reveal.
          e.preventDefault();
          e.stopPropagation();
          onReveal(id);
        };
        note.addEventListener("click", reveal);
        note.addEventListener("keydown", (e) => (e.key === "Enter" || e.key === " ") && reveal(e));
        el.prepend(note);
        el.classList.add("dil-collapsed");
      } else if (!hide && stub) {
        stub.remove();
        el.classList.remove("dil-collapsed");
      }
    }
  }

  function clearHiding() {
    document.querySelectorAll(".dil-stub").forEach((s) => s.remove());
    document.querySelectorAll(".dil-collapsed").forEach((el) => el.classList.remove("dil-collapsed"));
  }

  /** Scrolls to and flashes the first of these replies that is on the page; false if none is. */
  function scrollToReplies(replyIds) {
    const el = replyIds.map((id) => document.querySelector(`[data-dil-item="${CSS.escape(id)}"]`)).find(Boolean);
    if (!el) return false;
    el.scrollIntoView({ behavior: "smooth", block: "center" });
    el.classList.add("dil-flash");
    setTimeout(() => el.classList.remove("dil-flash"), 1800);
    return true;
  }

  window.DeadInternetLensCollector = { detect, scan, scanFeed, scanPosts, scrollDown, clickMore, decorate, clearBadges, applyHiding, clearHiding, scrollToReplies, ADAPTERS };
})();
