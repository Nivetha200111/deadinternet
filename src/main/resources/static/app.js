"use strict";
(() => {
  // ---------------------------------------------------------------- helpers
  const $ = (s, root = document) => root.querySelector(s);
  const $$ = (s, root = document) => [...root.querySelectorAll(s)];
  const clamp = (v, lo = 0, hi = 1) => Math.max(lo, Math.min(hi, v));
  const ease = (t) => {
    t = clamp(t);
    return t * t * (3 - 2 * t);
  };
  const pct = (v) => Math.round(v * 100) + "%";
  const fmt = (n) => Math.round(n).toLocaleString("en-US");
  const esc = (s) =>
    String(s ?? "").replace(
      /[&<>"']/g,
      (c) =>
        ({
          "&": "&amp;",
          "<": "&lt;",
          ">": "&gt;",
          '"': "&quot;",
          "'": "&#39;",
        })[c],
    );
  const hash = (s) => {
    let h = 2166136261;
    for (const c of s) h = Math.imul(h ^ c.charCodeAt(0), 16777619);
    return (h >>> 0) / 4294967295;
  };
  const reduceMotion = matchMedia("(prefers-reduced-motion: reduce)").matches;
  const params = new URLSearchParams(location.search);
  // Embedded in the browser-extension overlay: compact chrome, and selections are reported to the parent frame.
  const EMBED = params.has("embed") && window.parent !== window;
  if (EMBED) document.body.classList.add("embed");

  const cssVar = (name) =>
    getComputedStyle(document.documentElement).getPropertyValue(name).trim();
  const COLOR = {
    human_like: cssVar("--human"),
    uncertain: cssVar("--uncertain"),
    automation_like: cssVar("--automation"),
    text: cssVar("--text"),
    accent: cssVar("--accent"),
    neutral: "#888a95",
    similar: "#989aa3",
  };
  const LABEL = {
    human_like: "Human-like",
    uncertain: "Uncertain",
    automation_like: "Automation-like",
  };
  const TONE_CLASS = {
    human_like: "human",
    uncertain: "uncertain",
    automation_like: "automation",
  };
  const SOURCE_LABEL = {
    JEV: "JEV classifier",
    LOCAL_HEURISTIC: "Local heuristic (JEV not configured)",
    HEURISTIC_FALLBACK: "JEV failed: heuristic fallback, held at uncertain",
  };
  const rgba = (hex, a) => {
    const n = parseInt(hex.slice(1), 16);
    return `rgba(${n >> 16},${(n >> 8) & 255},${n & 255},${a})`;
  };

  async function api(path, options) {
    const response = await fetch(path, options);
    let body = null;
    try {
      body = await response.json();
    } catch {
      /* empty body */
    }
    if (!response.ok)
      throw new Error(
        body?.detail || body?.message || `Request failed (${response.status})`,
      );
    return body;
  }

  // ---------------------------------------------------------------- reveal timeline (ms)
  const PHASES = {
    discover: [250, 3200],
    compare: [2600, 4600],
    similar: [4300, 6600],
    coordinate: [6300, 8400],
    classify: [8200, 10000],
    cluster: [9800, 11600],
  };
  const TIMELINE_END = 12400;
  const phase = (name) => {
    const [a, b] = PHASES[name];
    return ease((state.t - a) / (b - a));
  };

  // ---------------------------------------------------------------- state
  const state = {
    graph: null,
    accounts: new Map(),
    order: [], // account ids by first reply time
    sensitivity: 50,
    filter: "all",
    focus: null, // { kind, ids: Set, clusterId? }
    pick: null, // path selection in progress: { from }
    path: null, // { steps, links, accountIds:Set }
    t: TIMELINE_END,
    playing: false,
    hovered: null,
    lastConversation: null, // for re-running an imported conversation
    reheat: 0,
    paused: false,
    anon: params.has("anon"), // replace handles with stable pseudonyms (for sharing recordings of real threads)
  };

  /** How an account is shown: its handle, or a stable pseudonym when anonymized. */
  function displayName(a) {
    if (!a) return "";
    if (!state.anon) return "@" + a.username;
    return (
      "account-" +
      Math.floor(hash(a.id) * 1679616)
        .toString(36)
        .padStart(4, "0")
    );
  }
  const nameOf = (id) => displayName(state.accounts.get(id));

  function setAnonymized(value) {
    state.anon = value;
    $("#anon-button").setAttribute("aria-pressed", String(value));
    if (state.graph) renderAccountList();
    if (cy)
      cy.nodes('[kind="account"]').forEach((n) =>
        n.data("label", nameOf(n.id())),
      );
    if (state.graph) {
      setPostLine(state.graph.post);
      renderSourceContext();
    }
    if (state.focus?.kind === "account") inspectAccount(state.focus.primary);
    else if (state.focus?.kind === "cluster")
      inspectCluster(state.focus.clusterId);
  }

  function setPostLine(post) {
    $("#post-line").textContent = post.text;
    $("#post-line").title = post.text;
    $("#post-author").textContent = state.anon
      ? "Original author"
      : "@" + post.author;
    $("#post-handle").textContent = state.graph?.analysis.demo
      ? "Fictional sample conversation"
      : "Original conversation";
    $("#post-avatar").textContent = state.anon
      ? "◉"
      : post.author.slice(0, 2).toLowerCase();
    $("#post-date").textContent = new Date(post.createdAt)
      .toLocaleDateString("en-US", {
        month: "short",
        day: "2-digit",
        year: "numeric",
      })
      .toUpperCase();
  }

  const stage = $("#stage");
  const underlay = $("#underlay"),
    overlay = $("#overlay");
  const uctx = underlay.getContext("2d"),
    octx = overlay.getContext("2d");
  let W = 0,
    H = 0,
    dpr = 1,
    cy = null;

  // ---------------------------------------------------------------- classification at current sensitivity
  function thresholds() {
    const base = state.graph?.thresholds ?? { human: 0.4, automation: 0.65 };
    const shift = ((state.sensitivity - 50) / 50) * 0.2;
    const automation = clamp(base.automation - shift, 0.3, 0.95);
    const human = clamp(base.human - shift, 0.05, automation - 0.05);
    return { human, automation };
  }
  function visualClass(a) {
    if (a.classificationSource === "HEURISTIC_FALLBACK") return "uncertain";
    const t = thresholds();
    return a.automationLikelihood < t.human
      ? "human_like"
      : a.automationLikelihood < t.automation
        ? "uncertain"
        : "automation_like";
  }
  function clusterClass(c) {
    const t = thresholds();
    return c.averageAutomation < t.human
      ? "human_like"
      : c.averageAutomation < t.automation
        ? "uncertain"
        : "automation_like";
  }
  function passesFilter(a) {
    if (state.filter === "all") return true;
    if (state.filter === "coordinated")
      return Boolean(a.clusterId) || a.coordinatedAccounts > 0;
    return visualClass(a) === state.filter;
  }
  const inFocus = (id) => !state.focus || state.focus.ids.has(id);
  const isActive = (a) => passesFilter(a) && inFocus(a.id);

  // ---------------------------------------------------------------- force simulation
  // Positions live here, in Cytoscape model coordinates; Cytoscape only renders them.
  const sim = new Map();
  let orderIndex = new Map();
  const center = () => ({ x: W * 0.48, y: H * 0.46 });
  const anchors = new Map();

  // Scores set the left-to-right ordering. The vertical field separates real graph components.
  // Shrinks spacing, node size and forces as a thread grows (1 for ~90 accounts, 0.5 at ~360+).
  let density = 1;

  // Each cluster gets a disc sized by its membership, placed left-to-right by average automation likelihood;
  // overlapping discs are pushed apart so clusters stay distinct even with dozens of them. Accounts outside
  // clusters fill their classification's region around the discs.
  function layoutAnchors() {
    anchors.clear();
    const g = state.graph;
    density = clamp(Math.sqrt(90 / Math.max(1, g.accounts.length)), 0.5, 1);
    const c = center();
    const gap = 16 * density;
    const clusters = [...g.clusters].sort((a, b) => b.accountIds.length - a.accountIds.length);
    const few = clusters.length <= 4;
    // Never tighter than node sizes allow, even for very large threads.
    let spacing = 17 * Math.max(density, 0.75);
    const radius = (cl) => spacing * Math.sqrt(cl.accountIds.length) * 1.05 + 8;
    // If the discs can't fit in the space available, tighten them all evenly.
    const needed = clusters.reduce((sum, cl) => sum + Math.PI * (radius(cl) + gap) ** 2, 0);
    const available = W * H * 0.42;
    if (needed > available) spacing *= Math.sqrt(available / needed);
    const discs = clusters.map((cl, i) => ({
      cl,
      r: radius(cl),
      x: W * (0.17 + cl.averageAutomation * 0.72),
      // Few clusters keep the art-directed two rows; many spread over the height in a golden-ratio sequence.
      y: H * (few ? (i === 0 ? 0.32 : 0.74) : 0.16 + ((i * 0.618034) % 1) * 0.68),
    }));
    const postClear = 64;
    for (let iteration = 0; iteration < 260; iteration++) {
      for (let i = 0; i < discs.length; i++) {
        const a = discs[i];
        for (let j = i + 1; j < discs.length; j++) {
          const b = discs[j];
          let dx = b.x - a.x, dy = b.y - a.y, d = Math.hypot(dx, dy);
          const min = a.r + b.r + gap;
          if (d >= min) continue;
          if (d < 0.01) { dx = hash(a.cl.id) - 0.5; dy = hash(b.cl.id) - 0.5; d = Math.hypot(dx, dy); }
          const push = (min - d) / 2;
          a.x -= (dx / d) * push; a.y -= (dy / d) * push;
          b.x += (dx / d) * push; b.y += (dy / d) * push;
        }
        const dx = a.x - c.x, dy = a.y - c.y, d = Math.hypot(dx, dy) || 1;
        if (d < a.r + postClear) { a.x = c.x + (dx / d) * (a.r + postClear); a.y = c.y + (dy / d) * (a.r + postClear); }
        a.x = clamp(a.x, a.r + 30, W - a.r - 30);
        a.y = clamp(a.y, a.r + 30, H - a.r - 44);
      }
    }
    for (const disc of discs) {
      const members = [...disc.cl.accountIds].sort();
      members.forEach((id, i) => {
        const angle = i * 2.39996323 + 0.3, rad = Math.max(0, disc.r - 8) * Math.sqrt((i + 0.5) / members.length);
        anchors.set(id, { x: disc.x + Math.cos(angle) * rad, y: disc.y + Math.sin(angle) * rad });
      });
    }

    const regions = {
      human_like: { x: 0.225, y: 0.45, rx: 0.2, ry: 0.36 },
      uncertain: { x: 0.48, y: 0.55, rx: 0.12, ry: 0.3 },
      automation_like: { x: 0.88, y: 0.5, rx: 0.1, ry: 0.36 },
    };
    const loose = new Map();
    for (const a of g.accounts) {
      if (a.clusterId) continue;
      const k = visualClass(a);
      if (!loose.has(k)) loose.set(k, []);
      loose.get(k).push(a);
    }
    loose.forEach((members, key) => {
      const region = regions[key];
      members.sort((a, b) => a.id.localeCompare(b.id)).forEach((a, i) => {
        const angle = i * 2.39996323 + 0.8, rad = Math.sqrt((i + 0.7) / members.length);
        let x = W * clamp(region.x + Math.cos(angle) * region.rx * rad, 0.05, 0.95);
        let y = H * clamp(region.y + Math.sin(angle) * region.ry * rad, 0.08, 0.86);
        // Step outside any cluster disc this lands in.
        for (const disc of discs) {
          const dx = x - disc.x, dy = y - disc.y, d = Math.hypot(dx, dy) || 1;
          if (d < disc.r + gap) { x = disc.x + (dx / d) * (disc.r + gap); y = disc.y + (dy / d) * (disc.r + gap); }
        }
        anchors.set(a.id, { x: clamp(x, 24, W - 24), y: clamp(y, 24, H - 44) });
      });
    });
  }

  function seedPositions() {
    const c = center(),
      R = Math.min(W, H);
    layoutAnchors();
    sim.clear();
    sim.set("post", { x: c.x, y: c.y, vx: 0, vy: 0, pinned: true });
    state.order.forEach((id, i) => {
      const angle = hash(id) * Math.PI * 2;
      const r =
        R *
        (0.08 + 0.3 * Math.sqrt((i + 1) / state.order.length)) *
        (0.85 + hash(id + "r") * 0.3);
      sim.set(id, {
        x: c.x + Math.cos(angle) * r * 1.25,
        y: c.y + Math.sin(angle) * r * 0.9,
        vx: 0,
        vy: 0,
        pinned: false,
      });
    });
  }

  function discovered(id) {
    const i = orderIndex.get(id) ?? -1;
    return i >= 0 && phase("discover") >= (i + 1) / state.order.length;
  }

  function simulate() {
    const g = state.graph;
    if (!g) return 0;
    const c = center(),
      R = Math.min(W, H);
    const pSim = phase("similar"),
      pCoord = phase("coordinate"),
      pClass = phase("classify"),
      pCluster = phase("cluster");
    const live = state.order.filter(discovered).map((id) => [id, sim.get(id)]);
    const post = sim.get("post");
    post.x = c.x;
    post.y = c.y;

    // Pairwise repulsion keeps the cloud legible.
    for (let i = 0; i < live.length; i++) {
      const [, a] = live[i];
      for (let j = i + 1; j < live.length; j++) {
        const [, b] = live[j];
        let dx = a.x - b.x,
          dy = a.y - b.y,
          d2 = dx * dx + dy * dy;
        if (d2 < 1) {
          dx = hash(live[i][0]) - 0.5;
          dy = hash(live[j][0]) - 0.5;
          d2 = 1;
        }
        if (d2 > 40000) continue;
        // Force magnitude falls off with 1/d²: firm spacing up close, little long-range push.
        // Once classified, the anchor layout is authoritative; repulsion fades so clusters don't inflate.
        const f = (850 * Math.min(1, W / 1000) * density * density * (1 - 0.85 * pClass)) / (d2 * Math.sqrt(d2));
        a.vx += dx * f;
        a.vy += dy * f;
        b.vx -= dx * f;
        b.vy -= dy * f;
      }
      // Keep clear of the original post.
      const dx = a.x - post.x,
        dy = a.y - post.y,
        d2 = dx * dx + dy * dy + 1;
      const f = 22000 / (d2 * Math.sqrt(d2));
      a.vx += dx * f;
      a.vy += dy * f;
    }

    const spring = (a, b, rest, k) => {
      const dx = b.x - a.x,
        dy = b.y - a.y,
        d = Math.sqrt(dx * dx + dy * dy) || 1;
      const f = ((d - rest) * k) / d;
      if (!a.pinned) {
        a.vx += dx * f;
        a.vy += dy * f;
      }
      if (!b.pinned) {
        b.vx -= dx * f;
        b.vy -= dy * f;
      }
    };
    // Every reply tethers its account loosely to the original post.
    for (const [id, n] of live)
      spring(post, n, R * 0.3, 0.0016 * (1 - 0.93 * pClass));
    for (const e of g.edges) {
      const a = sim.get(e.source),
        b = sim.get(e.target);
      if (!discovered(e.source) || !discovered(e.target)) continue;
      if (e.type === "SIMILAR" && pSim > 0)
        spring(a, b, 44 * density, 0.004 * pSim * e.weight * (1 - 0.8 * pCluster));
      if (e.type === "COORDINATED" && pCoord > 0)
        spring(a, b, 32 * density, 0.008 * pCoord * e.weight * (1 - 0.87 * pCluster));
    }
    // Members of a cluster gather around their centroid once clusters are revealed.
    if (pCluster > 0) {
      for (const cl of g.clusters) {
        const members = cl.accountIds.map((id) => sim.get(id));
        const cx = members.reduce((s, m) => s + m.x, 0) / members.length;
        const cyy = members.reduce((s, m) => s + m.y, 0) / members.length;
        for (const m of members) {
          m.vx += (cx - m.x) * 0.0008 * pCluster;
          m.vy += (cyy - m.y) * 0.0008 * pCluster;
        }
      }
    }

    let energy = 0;
    for (const [id, n] of live) {
      const a = state.accounts.get(id);
      // Horizontal position encodes automation likelihood once accounts are classified.
      const target = anchors.get(id);
      n.vx +=
        (target.x - n.x) * 0.05 * pClass + (c.x - n.x) * 0.0015 * (1 - pClass);
      n.vy +=
        (target.y - n.y) * 0.05 * pClass + (c.y - n.y) * 0.002 * (1 - pClass);
      // Soft walls, plus keep-out zones under the progress and legend panels.
      const m = 32;
      if (n.x < m) n.vx += (m - n.x) * 0.05;
      if (n.x > W - m) n.vx -= (n.x - W + m) * 0.05;
      if (n.y < m) n.vy += (m - n.y) * 0.05;
      if (n.y > H - m - 30) n.vy -= (n.y - H + m + 30) * 0.05;
      for (const z of keepOut) {
        if (n.x > z.left && n.x < z.right && n.y > z.top && n.y < z.bottom)
          n.vy += (z.bottom - n.y) * 0.02;
      }
      if (n.pinned) {
        n.vx = 0;
        n.vy = 0;
        continue;
      }
      n.vx *= 0.82;
      n.vy *= 0.82;
      const speed = Math.hypot(n.vx, n.vy);
      if (speed > 14) {
        n.vx *= 14 / speed;
        n.vy *= 14 / speed;
      }
      n.x = clamp(n.x + n.vx, 24, W - 24);
      n.y = clamp(n.y + n.vy, 24, H - 44);
      energy += speed;
    }
    return energy;
  }

  // ---------------------------------------------------------------- cytoscape
  function nodeSize(a) {
    return (5.5 + 2.1 * Math.sqrt(a.degree + a.replyCount)) * (0.55 + 0.45 * density);
  }

  function buildGraph() {
    if (cy) cy.destroy();
    const g = state.graph;
    const elements = [{ group: "nodes", data: { id: "post", kind: "post" } }];
    for (const a of g.accounts) {
      elements.push({
        group: "nodes",
        data: { id: a.id, kind: "account", size: nodeSize(a) },
      });
      elements.push({
        group: "edges",
        data: { id: "r:" + a.id, source: "post", target: a.id, kind: "REPLY" },
      });
    }
    const clusterOf = new Map(g.accounts.map((a) => [a.id, a.clusterId]));
    g.edges.forEach((e, i) => {
      elements.push({
        group: "edges",
        data: {
          id: (e.type === "SIMILAR" ? "s:" : "c:") + i,
          source: e.source,
          target: e.target,
          kind: e.type,
          weight: e.weight,
          order: hash(e.source + e.target),
          cluster: clusterOf.get(e.source) || "",
        },
      });
    });

    cy = cytoscape({
      container: $("#cy"),
      elements,
      layout: { name: "preset" },
      minZoom: 0.4,
      maxZoom: 3.5,
      boxSelectionEnabled: false,
      autounselectify: true,
      style: [
        {
          selector: "node",
          style: {
            width: "data(size)",
            height: "data(size)",
            "background-color": COLOR.neutral,
            "border-width": 0,
            "border-color": COLOR.neutral,
            "overlay-opacity": 0,
            label: "",
            "font-family": "IBM Plex Mono",
            "font-size": 9,
            color: COLOR.text,
            "text-valign": "center",
            "text-halign": "right",
            "text-margin-x": 8,
            "text-outline-color": "#0c0c0d",
            "text-outline-width": 2,
            "text-outline-opacity": 0.9,
          },
        },
        {
          selector: 'node[kind="post"]',
          style: {
            width: 22,
            height: 22,
            shape: "diamond",
            "background-color": COLOR.accent,
            "border-width": 1,
            "border-color": "#ffffff",
            label: state.graph.analysis.kind === "feed" ? "YOUR FEED" : "ORIGINAL POST",
            "text-valign": "bottom",
            "text-halign": "center",
            "text-margin-y": 10,
            "text-margin-x": 0,
            "font-size": 7,
            color: "#92949e",
          },
        },
        { selector: "node.labeled", style: { label: "data(label)" } },
        {
          selector: "edge",
          style: {
            width: 0.6,
            "line-color": COLOR.text,
            opacity: 0,
            "curve-style": "haystack",
            "haystack-radius": 0,
            "overlay-opacity": 0,
          },
        },
        {
          selector: 'edge[kind="SIMILAR"]',
          style: {
            "curve-style": "straight",
            "line-style": "dashed",
            "line-dash-pattern": [2, 4],
            "line-color": COLOR.similar,
            width: "mapData(weight, 0.7, 1, 0.6, 1.2)",
          },
        },
        {
          selector: 'edge[kind="COORDINATED"]',
          style: { width: "mapData(weight, 0.75, 1, 0.6, 1.15)" },
        },
      ],
    });
    cy.nodes('[kind="account"]').forEach((n) =>
      n.data("label", nameOf(n.id())),
    );

    cy.on("tap", "node", (e) => {
      const id = e.target.id();
      if (id === "post") {
        showBanner(
          "The original post. Every account here replied to it; that shared link is not evidence of anything.",
          4200,
        );
        return;
      }
      if (state.pick) {
        pickForPath(id);
        return;
      }
      inspectAccount(id);
    });
    cy.on("tap", (e) => {
      if (e.target === cy) {
        if (state.pick) cancelPick();
        else clearFocus();
      }
    });
    cy.on("mouseover", 'node[kind="account"]', (e) => {
      state.hovered = e.target.id();
      // Hovering any member reveals its cluster's full label, even when decluttering hid it.
      state.hoveredCluster = state.accounts.get(e.target.id())?.clusterId ?? null;
      showTip(e.target);
      stage.style.cursor = "pointer";
    });
    cy.on("mouseout", "node", () => {
      state.hovered = null;
      state.hoveredCluster = null;
      $("#tip").hidden = true;
      stage.style.cursor = "";
    });
    cy.on("grab", 'node[kind="account"]', (e) => {
      const n = sim.get(e.target.id());
      if (n) n.pinned = true;
    });
    cy.on("drag", 'node[kind="account"]', (e) => {
      const n = sim.get(e.target.id()),
        p = e.target.position();
      if (n) {
        n.x = p.x;
        n.y = p.y;
      }
    });
    cy.on("free", 'node[kind="account"]', (e) => {
      const n = sim.get(e.target.id());
      if (n) n.pinned = false;
      state.reheat = 90;
    });
  }

  // Applies positions and appearance for the current timeline position, filter, focus and sensitivity.
  function render() {
    if (!cy || !state.graph) return;
    const pReply = phase("discover"),
      pSim = phase("similar"),
      pCoord = phase("coordinate"),
      pClass = phase("classify");
    const highlight = state.path ? state.path.accountIds : null;
    cy.batch(() => {
      const post = sim.get("post");
      cy.getElementById("post").position({ x: post.x, y: post.y });
      for (const node of cy.nodes('[kind="account"]')) {
        const id = node.id(),
          a = state.accounts.get(id),
          n = sim.get(id);
        if (!node.grabbed()) node.position({ x: n.x, y: n.y });
        const shown = discovered(id);
        const classified = pClass >= hash(id + "c") * 0.85;
        const kind = classified && pClass > 0 ? visualClass(a) : null;
        const active = highlight ? highlight.has(id) : isActive(a);
        const opacity = shown ? (active ? 1 : 0.08) : 0;
        const color = kind ? COLOR[kind] : COLOR.neutral;
        apply(node, {
          opacity,
          "background-color": color,
          "background-opacity": kind === "uncertain" ? 0.12 : 0.92,
          "border-width": kind === "uncertain" ? 1.6 : 0,
          "border-color": color,
          shape: kind === "automation_like" ? "diamond" : "ellipse",
          "underlay-color": color,
          "underlay-padding": kind === "automation_like" ? 5 : 3,
          "underlay-opacity":
            kind === "automation_like"
              ? 0.07 * opacity
              : kind === "human_like"
                ? 0.035 * opacity
                : 0,
          "underlay-shape": "ellipse",
        });
        node.toggleClass(
          "labeled",
          state.hovered === id ||
            (highlight && highlight.has(id)) ||
            state.focus?.primary === id,
        );
      }
      for (const edge of cy.edges()) {
        const kind = edge.data("kind"),
          s = edge.source().id(),
          t = edge.target().id();
        const sa = state.accounts.get(s),
          ta = state.accounts.get(t);
        if (!discovered(t) || (sa && !discovered(s))) {
          apply(edge, { opacity: 0 });
          continue;
        }
        let opacity,
          color = COLOR.text;
        if (kind === "REPLY") {
          opacity = 0.1 * pReply;
          if (state.focus || state.filter !== "all" || highlight)
            opacity *= isActive(ta) && !highlight ? 1 : 0.2;
        } else {
          const p = kind === "SIMILAR" ? pSim : pCoord;
          const local = clamp((p - edge.data("order") * 0.6) / 0.4);
          opacity = (kind === "SIMILAR" ? 0.2 : 0.31) * local;
          if (kind === "COORDINATED") {
            const cluster = state.graph.clusters.find(
              (c) => c.id === edge.data("cluster"),
            );
            color =
              cluster && pClass > 0.5
                ? COLOR[clusterClass(cluster)]
                : COLOR.text;
          } else color = COLOR.similar;
          const active = highlight ? false : isActive(sa) && isActive(ta);
          if (!active) opacity *= 0.06;
        }
        apply(edge, { opacity, "line-color": color });
      }
    });
  }

  // Only touch Cytoscape styles that actually changed; per-frame restyling is the main cost.
  function apply(ele, styles) {
    const cache = ele.scratch("_s") || {};
    const changed = {};
    let any = false;
    for (const [k, v] of Object.entries(styles)) {
      const old = cache[k];
      if (
        typeof v === "number"
          ? old === undefined || Math.abs(old - v) > 0.004
          : old !== v
      ) {
        changed[k] = v;
        cache[k] = v;
        any = true;
      }
    }
    if (any) {
      ele.style(changed);
      ele.scratch("_s", cache);
    }
  }

  // ---------------------------------------------------------------- canvas layers
  let keepOut = [];
  function resize() {
    W = stage.clientWidth;
    H = stage.clientHeight;
    dpr = Math.min(window.devicePixelRatio || 1, 2);
    const origin = stage.getBoundingClientRect();
    keepOut = ["#legend"]
      .map((s) => $(s).getBoundingClientRect())
      .filter((r) => r.width > 0)
      .map((r) => ({
        left: r.left - origin.left - 16,
        right: r.right - origin.left + 16,
        top: 0,
        bottom: r.bottom - origin.top + 18,
      }));
    for (const c of [underlay, overlay]) {
      c.width = W * dpr;
      c.height = H * dpr;
      c.getContext("2d").setTransform(dpr, 0, 0, dpr, 0, 0);
    }
    if (cy) cy.resize();
    if (state.graph) layoutAnchors();
    state.reheat = 120;
  }
  new ResizeObserver(resize).observe(stage);

  function convexHull(points) {
    const p = [...points].sort((a, b) => a.x - b.x || a.y - b.y);
    if (p.length < 3) return p;
    const cross = (o, a, b) =>
      (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x);
    const lower = [],
      upper = [];
    for (const q of p) {
      while (lower.length >= 2 && cross(lower.at(-2), lower.at(-1), q) <= 0)
        lower.pop();
      lower.push(q);
    }
    for (const q of p.reverse()) {
      while (upper.length >= 2 && cross(upper.at(-2), upper.at(-1), q) <= 0)
        upper.pop();
      upper.push(q);
    }
    return lower.slice(0, -1).concat(upper.slice(0, -1));
  }

  // Largest clusters get full labels first; a label that would overlap one already placed shrinks to a short
  // tag (C07), and if even that collides it is hidden until the cluster or one of its accounts is hovered.
  function placeClusterLabels(placements) {
    const placed = [];
    let fullLabels = 0;
    const overlaps = (r) => placed.some((q) => r.x1 < q.x2 && r.x2 > q.x1 && r.y1 < q.y2 && r.y2 > q.y1);
    const rect = (p, w) => ({ x1: p.x - w / 2 - 4, x2: p.x + w / 2 + 4, y1: p.y - 24, y2: p.y + 2 });
    placements.sort((a, b) => {
      const pinned = (p) => (state.focus?.clusterId === p.c.id || state.hoveredCluster === p.c.id ? 1 : 0);
      return pinned(b) - pinned(a) || b.c.accountIds.length - a.c.accountIds.length;
    });
    for (const p of placements) {
      const label = $(`#cluster-labels [data-cluster="${p.c.id}"]`);
      if (!label) continue;
      label.fullWidth ??= label.offsetWidth || 150;
      const pinned = state.focus?.clusterId === p.c.id || state.hoveredCluster === p.c.id;
      // Beyond the eight largest clusters, labels stay short so a big thread doesn't drown in text.
      let mode = pinned || fullLabels < 8 ? "full" : "compact";
      if (!pinned && mode === "full" && overlaps(rect(p, label.fullWidth))) mode = "compact";
      if (!pinned && mode === "compact" && overlaps(rect(p, 38))) mode = "hidden";
      if (mode === "full") fullLabels++;
      if (mode !== "hidden") placed.push(rect(p, mode === "full" ? label.fullWidth : 38));
      label.style.left = p.x + "px";
      label.style.top = p.y + "px";
      label.style.opacity = mode === "hidden" ? 0 : p.alpha;
      label.style.pointerEvents = mode !== "hidden" && p.alpha > 0.4 ? "auto" : "none";
      label.className = `cluster-label ${TONE_CLASS[clusterClass(p.c)]}${mode === "compact" ? " compact" : ""}${state.focus?.clusterId === p.c.id ? " selected" : ""}`;
    }
  }

  function drawUnderlay(time) {
    uctx.clearRect(0, 0, W, H);
    // Decorative dust is sub-pixel and never interactive; only real accounts get full-size nodes.
    for (let i = 0; i < 350; i++) {
      uctx.fillStyle = rgba("#9698a1", 0.06 + hash("alpha" + i) * 0.17);
      uctx.fillRect(
        hash("x" + i) * W,
        hash("y" + i) * H,
        i % 9 === 0 ? 1 : 0.6,
        i % 9 === 0 ? 1 : 0.6,
      );
    }
    uctx.strokeStyle = "#9698a10d";
    uctx.lineWidth = 0.6;
    for (let x = 30; x < W; x += 82)
      for (let y = 38; y < H; y += 82) {
        uctx.beginPath();
        uctx.moveTo(x - 2, y);
        uctx.lineTo(x + 2, y);
        uctx.moveTo(x, y - 2);
        uctx.lineTo(x, y + 2);
        uctx.stroke();
      }
    if (!cy || !state.graph) return;
    const zoom = cy.zoom();
    const post = cy.getElementById("post").renderedPosition();
    // Pulse from the original post while the conversation is being traced.
    if (state.playing && !reduceMotion) {
      const r = ((time * 0.06) % 260) * zoom;
      uctx.beginPath();
      uctx.arc(post.x, post.y, r, 0, Math.PI * 2);
      uctx.strokeStyle = rgba(COLOR.text, 0.1 * (1 - r / (260 * zoom)));
      uctx.lineWidth = 1;
      uctx.stroke();
    }
    const rootGlow = uctx.createRadialGradient(
      post.x,
      post.y,
      0,
      post.x,
      post.y,
      92 * zoom,
    );
    rootGlow.addColorStop(0, rgba(COLOR.accent, 0.16));
    rootGlow.addColorStop(0.3, rgba(COLOR.accent, 0.035));
    rootGlow.addColorStop(1, rgba(COLOR.accent, 0));
    uctx.fillStyle = rootGlow;
    uctx.fillRect(
      post.x - 100 * zoom,
      post.y - 100 * zoom,
      200 * zoom,
      200 * zoom,
    );
    for (const ring of [25, 38, 57]) {
      uctx.beginPath();
      uctx.arc(post.x, post.y, ring * zoom, 0, Math.PI * 2);
      uctx.strokeStyle = rgba(COLOR.accent, ring === 25 ? 0.28 : 0.08);
      uctx.lineWidth = 1;
      uctx.stroke();
    }

    // Cluster hulls.
    const pCluster = phase("cluster");
    const placements = [];
    for (const c of state.graph.clusters) {
      const tone = COLOR[clusterClass(c)];
      const focused =
        !state.focus || c.accountIds.some((id) => state.focus.ids.has(id));
      const filtered =
        state.filter === "all" ||
        state.filter === "coordinated" ||
        c.accountIds.some((id) => passesFilter(state.accounts.get(id)));
      const alpha = pCluster * (focused && filtered && !state.path ? 1 : 0.15);
      const pad = Math.max(10, 20 * density) * zoom;
      const pts = [];
      for (const id of c.accountIds) {
        const node = cy.getElementById(id),
          p = node.renderedPosition(),
          r = node.renderedWidth() / 2 + pad;
        for (let k = 0; k < 10; k++)
          pts.push({
            x: p.x + Math.cos((k / 10) * Math.PI * 2) * r,
            y: p.y + Math.sin((k / 10) * Math.PI * 2) * r,
          });
      }
      const hull = convexHull(pts);
      if (hull.length > 2) {
        const cx = hull.reduce((sum, p) => sum + p.x, 0) / hull.length,
          cyy = hull.reduce((sum, p) => sum + p.y, 0) / hull.length;
        const r =
          Math.max(60, ...hull.map((p) => Math.hypot(p.x - cx, p.y - cyy))) *
          1.5;
        const glow = uctx.createRadialGradient(cx, cyy, 0, cx, cyy, r);
        glow.addColorStop(0, rgba(tone, 0.1 * alpha));
        glow.addColorStop(0.6, rgba(tone, 0.025 * alpha));
        glow.addColorStop(1, rgba(tone, 0));
        uctx.fillStyle = glow;
        uctx.fillRect(cx - r, cyy - r, r * 2, r * 2);
      }
      if (alpha > 0.01 && hull.length > 2) {
        uctx.beginPath();
        const mid = (a, b) => ({ x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 });
        let start = mid(hull.at(-1), hull[0]);
        uctx.moveTo(start.x, start.y);
        for (let i = 0; i < hull.length; i++) {
          const m = mid(hull[i], hull[(i + 1) % hull.length]);
          uctx.quadraticCurveTo(hull[i].x, hull[i].y, m.x, m.y);
        }
        uctx.closePath();
        uctx.fillStyle = rgba(tone, 0.018 * alpha);
        uctx.fill();
        uctx.setLineDash([3, 6]);
        uctx.strokeStyle = rgba(tone, 0.29 * alpha);
        uctx.lineWidth = 1;
        uctx.stroke();
        uctx.setLineDash([]);
      }
      if (hull.length) {
        const top = hull.reduce((best, p) => (p.y < best.y ? p : best), hull[0]);
        const cx = hull.reduce((s, p) => s + p.x, 0) / hull.length;
        placements.push({ c, x: clamp(cx, 90, W - 90), y: Math.max(26, top.y - 6), alpha });
      }
    }
    placeClusterLabels(placements);
    for (const a of state.graph.accounts) {
      if (!discovered(a.id)) continue;
      const node = cy.getElementById(a.id),
        p = node.renderedPosition(),
        r = node.renderedWidth() / 2;
      const color =
        phase("classify") > 0.5 ? COLOR[visualClass(a)] : COLOR.neutral;
      const alpha = isActive(a) ? 1 : 0.06;
      const glow = uctx.createRadialGradient(p.x, p.y, 0, p.x, p.y, r * 5);
      glow.addColorStop(0, rgba(color, 0.3 * alpha));
      glow.addColorStop(0.3, rgba(color, 0.085 * alpha));
      glow.addColorStop(1, rgba(color, 0));
      uctx.fillStyle = glow;
      uctx.fillRect(p.x - r * 5, p.y - r * 5, r * 10, r * 10);
      if (a.degree >= 8 || state.hovered === a.id) {
        uctx.strokeStyle = rgba(color, 0.24 * alpha);
        uctx.lineWidth = 0.6;
        uctx.beginPath();
        uctx.arc(p.x, p.y, r + 5 * zoom, 0, Math.PI * 2);
        uctx.stroke();
      }
      if (
        phase("classify") === 1 &&
        isActive(a) &&
        !a.clusterId &&
        hash(a.id + "label") > 0.89 &&
        W > 650
      ) {
        uctx.font = "7px 'IBM Plex Mono'";
        uctx.fillStyle = rgba(color, 0.65);
        uctx.fillText(displayName(a), p.x + r + 7, p.y + 3);
      }
    }
  }

  function drawOverlay(time) {
    octx.clearRect(0, 0, W, H);
    if (!cy || !state.graph) return;
    if (
      !reduceMotion &&
      phase("similar") > 0.2 &&
      !state.path &&
      !state.paused
    ) {
      cy.edges().forEach((edge, i) => {
        if (i % 7 !== 0 || Number(edge.style("opacity")) < 0.05) return;
        const a = edge.source().renderedPosition(),
          b = edge.target().renderedPosition(),
          t = (time * 0.00004 + hash(edge.id())) % 1;
        const color =
          edge.data("kind") === "REPLY"
            ? COLOR.accent
            : edge.style("line-color");
        octx.fillStyle = color;
        octx.globalAlpha = 0.58;
        octx.shadowColor = color;
        octx.shadowBlur = 5;
        octx.beginPath();
        octx.arc(
          a.x + (b.x - a.x) * t,
          a.y + (b.y - a.y) * t,
          0.8,
          0,
          Math.PI * 2,
        );
        octx.fill();
        octx.shadowBlur = 0;
        octx.globalAlpha = 1;
      });
    }
    if (!state.path) return;
    const points = pathPoints();
    if (points.length < 2) return;
    octx.save();
    octx.lineJoin = "round";
    octx.lineCap = "round";
    octx.strokeStyle = rgba(COLOR.text, 0.18);
    octx.lineWidth = 7;
    octx.beginPath();
    points.forEach((p, i) =>
      i ? octx.lineTo(p.x, p.y) : octx.moveTo(p.x, p.y),
    );
    octx.stroke();
    octx.strokeStyle = COLOR.text;
    octx.lineWidth = 1.6;
    octx.setLineDash([6, 6]);
    octx.lineDashOffset = reduceMotion ? 0 : -time * 0.03;
    octx.beginPath();
    points.forEach((p, i) =>
      i ? octx.lineTo(p.x, p.y) : octx.moveTo(p.x, p.y),
    );
    octx.stroke();
    octx.setLineDash([]);
    octx.font = "500 9.5px 'IBM Plex Mono'";
    octx.textAlign = "center";
    for (let i = 0; i < points.length; i++) {
      const p = points[i];
      if (p.type === "Reply") {
        octx.fillStyle = "#08090b";
        octx.strokeStyle = COLOR.text;
        octx.lineWidth = 1.2;
        octx.fillRect(p.x - 4, p.y - 4, 8, 8);
        octx.strokeRect(p.x - 4, p.y - 4, 8, 8);
      }
      if (i < points.length - 1) {
        const q = points[i + 1],
          rel = state.path.links[i];
        const label =
          rel.type === "SIMILAR_TO"
            ? `SIMILAR ${pct(rel.similarity)}`
            : rel.type === "COORDINATED_WITH"
              ? `COORDINATED ${pct(rel.score)}`
              : "POSTED";
        const mx = (p.x + q.x) / 2,
          my = (p.y + q.y) / 2 - 7;
        octx.fillStyle = "rgba(8, 9, 11, 0.8)";
        const w = octx.measureText(label).width + 8;
        octx.fillRect(mx - w / 2, my - 9, w, 13);
        octx.fillStyle = COLOR.text;
        octx.fillText(label, mx, my + 1);
      }
    }
    octx.restore();
  }

  // Reply steps are drawn as small squares just off the account that posted them.
  function pathPoints() {
    const steps = state.path.steps;
    return steps.map((s, i) => {
      const owner = cy.getElementById(s.accountId).renderedPosition();
      if (s.type === "Account") return { ...owner, type: "Account" };
      const neighbor =
        steps[i + 1]?.accountId !== s.accountId ? steps[i + 1] : steps[i - 1];
      const other = neighbor
        ? cy.getElementById(neighbor.accountId).renderedPosition()
        : owner;
      const dx = other.x - owner.x,
        dy = other.y - owner.y,
        d = Math.hypot(dx, dy) || 1;
      const off = Math.min(26 * cy.zoom(), d * 0.3);
      return {
        x: owner.x + (dx / d) * off,
        y: owner.y + (dy / d) * off,
        type: "Reply",
      };
    });
  }

  // ---------------------------------------------------------------- frame loop
  let last = 0,
    idleFrames = 0;
  function frame(time) {
    const dt = last ? Math.min(50, time - last) : 16;
    last = time;
    if (
      state.graph &&
      !document.hidden &&
      !document.body.classList.contains("lens-hidden")
    ) {
      if (state.playing) {
        state.t = Math.min(TIMELINE_END, state.t + dt);
        if (state.t >= TIMELINE_END) {
          state.playing = false;
          state.reheat = 240;
          finishReveal();
        }
        updateProgress();
        updateHeader();
      }
      const moving =
        !state.paused &&
        (state.playing || state.reheat > 0 || idleFrames < 180);
      if (moving) {
        // Catch up after dropped frames so the layout settles at the same pace on slow machines.
        let energy = 0;
        for (let i = Math.min(4, Math.max(1, Math.round(dt / 16))); i > 0; i--)
          energy = simulate();
        if (state.reheat > 0) state.reheat--;
        idleFrames =
          energy < 2 && !state.playing && state.reheat <= 0
            ? idleFrames + 1
            : 0;
      }
      render();
      drawUnderlay(time);
      drawOverlay(time);
    }
    requestAnimationFrame(frame);
  }
  requestAnimationFrame(frame);

  // ---------------------------------------------------------------- header, progress, legend
  function countsByClass() {
    const counts = { human_like: 0, uncertain: 0, automation_like: 0 };
    for (const a of state.graph.accounts) counts[visualClass(a)]++;
    return counts;
  }

  function updateHeader() {
    const g = state.graph;
    if (!g) return;
    const p = phase("classify"),
      counts = countsByClass();
    $("#count-human").textContent = p > 0 ? fmt(counts.human_like * p) : "–";
    $("#count-uncertain").textContent = p > 0 ? fmt(counts.uncertain * p) : "–";
    $("#count-automation").textContent =
      p > 0 ? fmt(counts.automation_like * p) : "–";
    const ps = phase("cluster");
    $("#score-value").textContent =
      ps > 0 ? Math.round(g.score.score * 100 * ps) : "–";
    $("#overlay-score").textContent = $("#score-value").textContent;
    $("#overlay-counts").textContent =
      `${g.stats.accounts} ACCOUNTS · ${g.stats.clusters} CLUSTERS`;
    $("#axis").classList.toggle("visible", p > 0.3);
    const t = thresholds();
    $("#sens-readout").textContent = `automation-like ≥ ${pct(t.automation)}`;
    $("#sens-number").textContent = state.sensitivity;
    $("#sensitivity").style.background =
      `linear-gradient(to right,var(--accent) ${state.sensitivity}%,#383a40 ${state.sensitivity}%)`;
    for (const [kind, short] of [
      ["human_like", "human"],
      ["uncertain", "uncertain"],
      ["automation_like", "automation"],
    ])
      $("#distribution-" + short).style.width =
        (counts[kind] / g.accounts.length) * 100 + "%";
    updateTimeline();
  }

  function updateProgress() {
    const g = state.graph,
      s = g.stats;
    const values = {
      accounts: s.accounts * phase("discover"),
      comparisons: s.comparisons * phase("compare"),
      similarityEdges: s.similarityEdges * phase("similar"),
      coordinationEdges: s.coordinationEdges * phase("coordinate"),
      classified: s.accounts * phase("classify"),
      clusters: s.clusters * phase("cluster"),
    };
    for (const [key, value] of Object.entries(values))
      $(`[data-count="${key}"]`).textContent = fmt(value);
    const steps = [
      "discover",
      "compare",
      "similar",
      "coordinate",
      "classify",
      "cluster",
    ];
    for (const step of steps) {
      const li = $(`[data-step="${step}"]`),
        [a, b] = PHASES[step];
      li.classList.toggle("active", state.t >= a && state.t < b);
      li.classList.toggle("done", state.t >= b);
    }
    $("#progress-title").textContent =
      state.t < TIMELINE_END
        ? `Analyzing ${fmt(s.replies)} ${state.graph.analysis.kind === "feed" ? "posts" : "replies"}`
        : `${fmt(s.replies)} ${state.graph.analysis.kind === "feed" ? "posts" : "replies"} analyzed`;
  }

  function showLiveProgress(status) {
    // Real pipeline progress while the backend is still working.
    const p = status.progress;
    $("#progress-title").textContent =
      `Analyzing ${fmt(p.replies)} replies · ${status.stage.toLowerCase().replace("_", " ")}`;
    const values = {
      accounts: p.accounts,
      comparisons: p.comparisons,
      similarityEdges: p.similarityEdges,
      coordinationEdges: p.coordinationEdges,
      classified: p.classified,
      clusters: p.clusters,
    };
    for (const [key, value] of Object.entries(values))
      $(`[data-count="${key}"]`).textContent = fmt(value);
    const reached =
      {
        DISCOVERING: 0,
        COMPARING: 1,
        CONNECTING: 3,
        WRITING_GRAPH: 3,
        CLASSIFYING: 4,
        CLUSTERING: 5,
        SCORING: 6,
        COMPLETE: 6,
      }[status.stage] ?? 0;
    [
      "discover",
      "compare",
      "similar",
      "coordinate",
      "classify",
      "cluster",
    ].forEach((step, i) => {
      $(`[data-step="${step}"]`).classList.toggle("done", i < reached);
      $(`[data-step="${step}"]`).classList.toggle("active", i === reached);
    });
    $("#progress-source").textContent =
      status.provider === "JEV" ? "· JEV" : "· local heuristic";
  }

  function updateTimeline() {
    const t = state.t;
    $("#timeline-range").value = t;
    $("#timeline-range").style.background =
      `linear-gradient(to right,var(--accent) ${(t / TIMELINE_END) * 100}%,#383a40 ${(t / TIMELINE_END) * 100}%)`;
    $("#timeline-time").textContent = (t / 1000).toFixed(1) + " / 12.4s";
    $("#timeline-phase").textContent =
      t < 3200
        ? "DISCOVERING ACCOUNTS"
        : t < 6600
          ? "COMPARING SIGNALS"
          : t < 9800
            ? "TRACING COORDINATION"
            : t < TIMELINE_END
              ? "REVEALING THE PATTERN"
              : "CONVERSATION MAPPED";
    $("#map-status").textContent =
      t < TIMELINE_END ? "REVEALING PATTERNS" : "ANALYSIS COMPLETE";
    $("#play-button").textContent = state.playing
      ? "Ⅱ"
      : t >= TIMELINE_END
        ? "↻"
        : "▷";
    $("#play-button").setAttribute(
      "aria-label",
      state.playing
        ? "Pause reveal"
        : t >= TIMELINE_END
          ? "Replay reveal"
          : "Resume reveal",
    );
    $("#graph-hint").style.opacity = t >= TIMELINE_END ? "1" : "0";
  }
  function finishReveal() {
    updateProgress();
    updateHeader();
    $("#progress").classList.add("dimmed");
  }

  // ---------------------------------------------------------------- loading analyses
  async function load(status) {
    // Poll until the pipeline finishes, mirroring its real progress.
    $("#progress").classList.remove("dimmed");
    $("#progress").open = status.status === "RUNNING";
    while (status.status === "RUNNING") {
      showLiveProgress(status);
      await new Promise((r) => setTimeout(r, 250));
      status = await api(`/api/analyses/${status.id}`);
    }
    if (status.status === "FAILED")
      throw new Error(status.error || "Analysis failed");
    const graph = await api(`/api/analyses/${status.id}/graph`);
    setGraph(graph);
    const url = new URL(location.href);
    if (graph.analysis.demo) url.searchParams.delete("analysis");
    else url.searchParams.set("analysis", graph.analysis.id);
    history.replaceState(null, "", url);
  }

  function setGraph(graph) {
    state.graph = graph;
    state.accounts = new Map(graph.accounts.map((a) => [a.id, a]));
    state.order = [...graph.accounts]
      .sort((a, b) => a.firstReplySeconds - b.firstReplySeconds)
      .map((a) => a.id);
    orderIndex = new Map(state.order.map((id, i) => [id, i]));
    state.focus = null;
    state.path = null;
    state.pick = null;
    state.filter = "all";
    $("#inspector").hidden = true;
    $("#dataset-badge").textContent = graph.analysis.demo ? "DEMO" : "IMPORTED";
    $("#total-accounts").textContent = graph.stats.accounts;
    $("#post-replies").textContent = graph.stats.replies + (graph.analysis.kind === "feed" ? " POSTS" : " REPLIES");
    $("#source-status").textContent =
      (graph.analysis.demo ? "SYNTHETIC DATA" : "CONVERSATION DATA") +
      " · " +
      (graph.analysis.provider === "JEV"
        ? "JEV CLASSIFIER"
        : "LOCAL HEURISTIC");
    $("#network-stats").textContent =
      `${graph.stats.accounts} ACCOUNTS  /  ${fmt(graph.stats.comparisons)} COMPARISONS  /  ${graph.stats.clusters} CLUSTERS`;
    const post = graph.post;
    setPostLine(post);
    renderSourceContext();
    $("#progress-source").textContent =
      graph.analysis.provider === "JEV" ? "· JEV" : "· local heuristic";
    $("#import-provider").textContent =
      graph.analysis.provider === "JEV"
        ? "Classification: JEV endpoint. If JEV fails for an account, it is held at uncertain and marked as fallback."
        : "Classification: local heuristic, because no JEV endpoint is configured (set JEV_URL to use JEV).";
    $("#cluster-labels").innerHTML = graph.clusters
      .map(
        (c) =>
          `<button class="cluster-label" data-cluster="${esc(c.id)}" title="Cluster ${String(c.index).padStart(2, "0")} · ${c.accountIds.length} accounts"><b class="full">Cluster ${String(c.index).padStart(2, "0")}</b><b class="short">C${String(c.index).padStart(2, "0")}</b><span>${c.accountIds.length} accounts</span></button>`,
      )
      .join("");
    $$("[data-cluster]").forEach((b) => {
      b.addEventListener("click", () => inspectCluster(b.dataset.cluster));
      b.addEventListener("mouseenter", () => (state.hoveredCluster = b.dataset.cluster));
      b.addEventListener("mouseleave", () => (state.hoveredCluster = null));
    });
    $("#rerun-button").disabled =
      !graph.analysis.demo && !state.lastConversation;
    resize();
    buildGraph();
    seedPositions();
    renderAccountList();
    syncFilterButtons();
    replay();
  }

  function replay() {
    if (!state.graph) return;
    clearFocus(false);
    state.paused = false;
    state.path = null;
    seedPositions();
    if (cy) {
      cy.zoom(1);
      cy.pan({ x: 0, y: 0 });
    }
    $("#progress").classList.remove("dimmed");
    $("#progress").open = false;
    if (reduceMotion) {
      state.t = TIMELINE_END;
      state.playing = false;
      state.paused = false;
      for (let i = 0; i < 400; i++) simulate();
      finishReveal();
    } else {
      state.t = 0;
      state.playing = true;
      updateProgress();
    }
    updateHeader();
  }

  // ---------------------------------------------------------------- inspectors
  function skipToEnd() {
    if (state.t < TIMELINE_END) {
      state.t = TIMELINE_END;
      state.playing = false;
      state.paused = false;
      for (let i = 0; i < 160; i++) simulate();
      finishReveal();
    }
  }

  function metric(label, value, color) {
    return `<div class="metric"><div class="metric-head"><span>${label}</span><strong>${pct(value)}</strong></div>
      <div class="bar"><i style="width:${value * 100}%;background:${color}"></i></div></div>`;
  }
  function list(items, cls = "") {
    return `<ul class="signals ${cls}">${items.length ? items.map((s) => `<li>${esc(s)}</li>`).join("") : '<li class="empty">None detected</li>'}</ul>`;
  }
  function openInspector(html) {
    $("#inspector-body").innerHTML = html;
    $("#inspector").hidden = false;
    $("#inspector").scrollTop = 0;
  }

  async function inspectAccount(id) {
    const a = state.accounts.get(id);
    if (!a) return;
    skipToEnd();
    state.path = null;
    const neighbors = state.graph.edges
      .filter((e) => e.source === id || e.target === id)
      .map((e) => (e.source === id ? e.target : e.source));
    state.focus = {
      kind: "account",
      ids: new Set([id, ...neighbors]),
      primary: id,
    };
    const kind = visualClass(a);
    const replies = `${a.replyCount} repl${a.replyCount === 1 ? "y" : "ies"} here`;
    const meta = [];
    if (a.accountAgeDays != null)
      meta.push(
        a.accountAgeDays >= 365
          ? `${(a.accountAgeDays / 365).toFixed(1)} years old`
          : `${a.accountAgeDays} days old`,
      );
    if (a.followers != null) meta.push(`${fmt(a.followers)} followers`);
    if (a.following != null) meta.push(`${fmt(a.following)} following`);
    if (!meta.length) meta.push("account metadata not available");
    openInspector(`
      <div class="eyebrow">Account</div>
      <h2>${esc(displayName(a))}</h2>
      <span class="badge ${kind}">${LABEL[kind]}</span>
      <p class="meta">${meta.join(" · ")} · ${replies}</p>
      ${a.replies
        .slice(0, 3)
        .map(
          (r) =>
            `<div class="quote">${esc(r.text)}<small>+${formatSeconds(r.secondsAfterParent)} after the post${r.duplicate ? " · near-duplicate" : ""}</small></div>`,
        )
        .join("")}
      ${metric("Automation likelihood", a.automationLikelihood, COLOR[kind])}
      ${metric("Coordination likelihood", a.coordinationLikelihood, COLOR.text)}
      ${metric("Confidence", a.confidence, "#565961")}
      <h3>Signals</h3>${list(a.signalsForAutomation)}
      <h3>Counter-signals</h3>${list(a.signalsAgainstAutomation, "counter")}
      <h3>Coordination</h3>${list(a.coordinationSignals, "coordination")}
      <div id="neighborhood"><h3>Graph neighborhood</h3><p class="note">Querying FalkorDB…</p></div>
      <p class="note">${esc(a.summary)}<br>Source: ${esc(SOURCE_LABEL[a.classificationSource] || a.classificationSource)}.</p>
      <div class="inspector-actions">
        <button class="btn" id="connect-from">Find connection from here</button>
        ${a.clusterId ? `<button class="btn ghost" id="open-cluster">Open ${esc(clusterName(a.clusterId))}</button>` : ""}
      </div>`);
    $("#connect-from").onclick = () => startPick(id);
    if (EMBED)
      window.parent.postMessage(
        {
          type: "lens:account",
          accountId: id,
          username: a.username,
          replyIds: a.replies.map((r) => r.id),
        },
        "*",
      );
    if (a.clusterId)
      $("#open-cluster").onclick = () => inspectCluster(a.clusterId);
    try {
      const detail = await api(
        `/api/accounts/${encodeURIComponent(id)}?analysis=${state.graph.analysis.id}`,
      );
      if (state.focus?.primary !== id) return;
      const similar = detail.similarReplies
        .map(
          (s) =>
            `<button class="neighbor" data-account="${esc(s.otherAccountId)}" title="${esc(s.otherText)}"><span>${esc(nameOf(s.otherAccountId))} · ${formatSeconds(s.timeDifferenceSeconds)} apart</span><span>${pct(s.similarity)}</span></button>`,
        )
        .join("");
      const cluster = detail.cluster
        ? `<p class="meta">Member of ${esc(clusterName(detail.cluster.id))}: ${detail.cluster.accountIds.length} accounts, ${pct(detail.cluster.averageSimilarity)} average text similarity.</p>`
        : "";
      $("#neighborhood").innerHTML = `<h3>Graph neighborhood · FalkorDB</h3>
        <p class="meta">Coordinated with ${detail.coordinatedWith.length} account${detail.coordinatedWith.length === 1 ? "" : "s"} · ${detail.reachableWithinTwoHops} reachable within two coordination hops</p>
        ${cluster}
        ${similar ? `<div class="neighbors" style="margin-top:8px">${similar}</div>` : '<p class="meta">No similar replies from other accounts.</p>'}`;
      $$("#neighborhood [data-account]").forEach(
        (b) => (b.onclick = () => inspectAccount(b.dataset.account)),
      );
    } catch (e) {
      $("#neighborhood").innerHTML =
        `<h3>Graph neighborhood</h3><p class="note">${esc(e.message)}</p>`;
    }
  }

  async function inspectCluster(clusterId) {
    const c = state.graph.clusters.find((x) => x.id === clusterId);
    if (!c) return;
    skipToEnd();
    state.path = null;
    state.focus = { kind: "cluster", ids: new Set(c.accountIds), clusterId };
    const kind = clusterClass(c);
    const humanCoordination = kind === "human_like";
    openInspector(`
      <div class="eyebrow">Behavioral cluster</div>
      <h2>${esc(clusterName(c.id))}</h2>
      <span class="badge ${kind}">Coordinated · ${LABEL[kind].toLowerCase()} signals</span>
      <div class="stats-grid">
        <div class="stat">Accounts<strong>${c.accountIds.length}</strong></div>
        <div class="stat">Reply time span<strong>${formatSeconds(c.timeSpanSeconds)}</strong></div>
      </div>
      ${metric("Average automation likelihood", c.averageAutomation, COLOR[kind])}
      ${metric("Average coordination", c.averageCoordination, COLOR.text)}
      ${metric("Average text similarity", c.averageSimilarity, COLOR.similar)}
      <h3>Repeated patterns</h3>
      <div class="patterns">${c.patterns.length ? c.patterns.map((p) => `<q>${esc(p)}</q>`).join("") : '<span class="meta">Similar wording, no repeated phrase</span>'}</div>
      <div id="cluster-detail"><p class="note">Querying FalkorDB…</p></div>
      <p class="note">${
        humanCoordination
          ? "These accounts coordinate, but their individual signals look human-like. People organizing together is coordination, not automation."
          : "Grouped by shared text, timing and phrases. A cluster is a behavioral pattern, not evidence of who runs these accounts or why."
      }</p>`);
    if (cy && !reduceMotion)
      cy.animate(
        {
          fit: {
            eles: cy.collection(
              c.accountIds.map((id) => cy.getElementById(id)),
            ),
            padding: Math.min(W, H) * 0.28,
          },
        },
        { duration: 650, easing: "ease-in-out-cubic" },
      );
    try {
      const detail = await api(
        `/api/clusters/${encodeURIComponent(clusterId)}?analysis=${state.graph.analysis.id}`,
      );
      if (state.focus?.clusterId !== clusterId) return;
      $("#cluster-detail").innerHTML = `<h3>Members · FalkorDB</h3>
        <p class="meta">${detail.coordinationEdges} coordination edges · ${pct(detail.density)} density</p>
        <div class="neighbors" style="margin-top:8px">${detail.members
          .map(
            (m) =>
              `<button class="neighbor" data-account="${esc(m.accountId)}"><span>${esc(nameOf(m.accountId))}</span><span>${pct(m.automationLikelihood)} · ${pct(m.coordinationLikelihood)}</span></button>`,
          )
          .join("")}</div>
        <p class="meta">automation · coordination</p>`;
      $$("#cluster-detail [data-account]").forEach(
        (b) => (b.onclick = () => inspectAccount(b.dataset.account)),
      );
    } catch (e) {
      $("#cluster-detail").innerHTML = `<p class="note">${esc(e.message)}</p>`;
    }
  }

  function clearFocus(resetView = true) {
    const hadZoom = state.focus?.kind === "cluster" || Boolean(state.path);
    state.focus = null;
    state.path = null;
    $("#inspector").hidden = true;
    if (resetView && hadZoom && cy && !reduceMotion)
      cy.animate(
        { zoom: 1, pan: { x: 0, y: 0 } },
        { duration: 550, easing: "ease-in-out-cubic" },
      );
  }

  const clusterName = (id) => {
    const c = state.graph.clusters.find((x) => x.id === id);
    return c ? `Cluster ${String(c.index).padStart(2, "0")}` : id;
  };
  function formatSeconds(s) {
    if (s < 90) return `${s}s`;
    if (s < 3600) return `${Math.round(s / 60)} min`;
    return `${(s / 3600).toFixed(1)} h`;
  }

  // ---------------------------------------------------------------- path explorer
  function startPick(from = null) {
    if (!state.graph) return;
    skipToEnd();
    clearFocus();
    state.pick = { from };
    $("#path-button").classList.add("armed");
    showBanner(
      from
        ? `From ${nameOf(from)}: now select the second account`
        : "Select the first account",
      0,
      true,
    );
  }
  function cancelPick() {
    state.pick = null;
    $("#path-button").classList.remove("armed");
    hideBanner();
  }
  async function pickForPath(id) {
    if (!state.pick.from) {
      state.pick.from = id;
      state.focus = { kind: "pick", ids: new Set([id]), primary: id };
      showBanner(`From ${nameOf(id)}: now select the second account`, 0, true);
      return;
    }
    if (id === state.pick.from) return;
    const from = state.pick.from;
    cancelPick();
    try {
      const path = await api(
        `/api/path?analysis=${state.graph.analysis.id}&from=${encodeURIComponent(from)}&to=${encodeURIComponent(id)}`,
      );
      showPath(from, id, path);
    } catch (e) {
      showBanner(e.message, 5000, false, true);
    }
  }
  function showPath(from, to, path) {
    const a = state.accounts.get(from),
      b = state.accounts.get(to);
    if (!path.found) {
      state.focus = { kind: "pick", ids: new Set([from, to]) };
      openInspector(`<div class="eyebrow">Graph path · FalkorDB</div><h2>No connection</h2>
        <p class="meta">${esc(displayName(a))} and ${esc(displayName(b))} are not linked by similar text or coordination. Replying to the same post doesn't count.</p>`);
      return;
    }
    state.focus = null;
    state.path = {
      steps: path.nodes,
      links: path.relationships,
      accountIds: new Set(path.nodes.map((n) => n.accountId)),
    };
    if (cy && !reduceMotion) {
      const eles = cy.collection(
        [...state.path.accountIds].map((id) => cy.getElementById(id)),
      );
      const box = eles.boundingBox();
      const level = clamp(
        Math.min(
          (W * 0.45) / Math.max(box.w, 1),
          (H * 0.45) / Math.max(box.h, 1),
        ),
        1,
        2.2,
      );
      cy.animate(
        { zoom: level, center: { eles } },
        { duration: 650, easing: "ease-in-out-cubic" },
      );
    }
    const relLabel = (rel, i) => {
      const next = path.nodes[i + 1];
      if (rel.type === "POSTED")
        return next.type === "Reply" ? "↓ POSTED" : "↓ POSTED BY";
      if (rel.type === "SIMILAR_TO")
        return `↓ SIMILAR_TO · ${pct(rel.similarity)} · ${formatSeconds(rel.timeDifferenceSeconds)} apart`;
      return `↓ COORDINATED_WITH · ${pct(rel.score)}`;
    };
    openInspector(`<div class="eyebrow">Graph path · FalkorDB algo.SPpaths</div>
      <h2>Connection found</h2>
      <span class="badge">${path.relationships.length} relationships</span>
      <div class="path-steps">${path.nodes
        .map(
          (n, i) => `
        <div class="path-step ${n.type.toLowerCase()}">${n.type === "Account" ? esc(nameOf(n.id)) : `“${esc(n.label)}”`}</div>
        ${i < path.relationships.length ? `<div class="path-rel">${relLabel(path.relationships[i], i)}</div>` : ""}`,
        )
        .join("")}</div>
      <p class="note">The path follows observed behavioral relationships only. It does not establish who controls either account.</p>`);
  }

  // ---------------------------------------------------------------- tooltip and banner
  function showTip(node) {
    const a = state.accounts.get(node.id()),
      p = node.renderedPosition(),
      tip = $("#tip");
    const kind =
      discovered(a.id) && phase("classify") > 0
        ? LABEL[visualClass(a)]
        : "Classifying…";
    tip.innerHTML = `${esc(displayName(a))}<small>${kind} · ${pct(a.automationLikelihood)} automation · ${pct(a.coordinationLikelihood)} coordination</small>`;
    tip.style.left = Math.max(8, Math.min(W - 280, p.x + 14)) + "px";
    tip.style.top = Math.max(8, p.y - 52) + "px";
    tip.hidden = false;
  }
  let bannerTimer;
  function showBanner(message, ms = 3500, cancellable = false, error = false) {
    const banner = $("#banner");
    banner.innerHTML = `<span>${esc(message)}</span>${cancellable ? '<button id="banner-cancel">Cancel</button>' : ""}`;
    banner.classList.toggle("error", error);
    banner.hidden = false;
    if (cancellable) $("#banner-cancel").onclick = cancelPick;
    clearTimeout(bannerTimer);
    if (ms) bannerTimer = setTimeout(hideBanner, ms);
  }
  function hideBanner() {
    $("#banner").hidden = true;
  }

  // ---------------------------------------------------------------- controls
  function syncFilterButtons() {
    $$("[data-filter]").forEach((b) => {
      const on = b.dataset.filter === state.filter;
      b.classList.toggle(
        "active",
        (on && b.classList.contains("chip")) ||
          (on && b.classList.contains("tally-item") && state.filter !== "all"),
      );
      b.setAttribute("aria-pressed", String(on));
    });
  }
  $$("[data-filter]").forEach((b) =>
    b.addEventListener("click", () => {
      if (!state.graph) return;
      skipToEnd();
      state.filter =
        state.filter === b.dataset.filter && b.classList.contains("tally-item")
          ? "all"
          : b.dataset.filter;
      clearFocus();
      syncFilterButtons();
    }),
  );

  $("#sensitivity").addEventListener("input", (e) => {
    state.sensitivity = Number(e.target.value);
    updateHeader();
  });

  $("#replay-button").onclick = replay;
  $("#path-button").onclick = () => (state.pick ? cancelPick() : startPick());
  $("#inspector-close").onclick = () => clearFocus();
  $("#method-button").onclick = () => $("#method-dialog").showModal();

  $("#rerun-button").onclick = async () => {
    const button = $("#rerun-button");
    button.disabled = true;
    try {
      const status =
        state.graph?.analysis.demo || !state.lastConversation
          ? await api("/api/demo/reset", { method: "POST" })
          : await api("/api/analyses", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify(state.lastConversation),
            });
      await load(status);
    } catch (e) {
      showBanner(e.message, 6000, false, true);
    } finally {
      button.disabled = false;
    }
  };

  $("#import-button").onclick = () => {
    $("#import-error").textContent = "";
    $("#import-dialog").showModal();
  };
  $("#import-sample").onclick = async () => {
    try {
      $("#import-json").value = JSON.stringify(
        await api("/api/demo/conversation"),
        null,
        2,
      );
    } catch (e) {
      $("#import-error").textContent = e.message;
    }
  };
  $("#import-submit").onclick = async () => {
    const button = $("#import-submit");
    $("#import-error").textContent = "";
    let conversation;
    try {
      conversation = JSON.parse($("#import-json").value);
    } catch {
      $("#import-error").textContent =
        "That is not valid JSON. Check brackets, quotes and commas.";
      return;
    }
    button.disabled = true;
    try {
      const status = await api("/api/analyses", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(conversation),
      });
      state.lastConversation = conversation;
      $("#import-dialog").close();
      await load(status);
    } catch (e) {
      $("#import-error").textContent = e.message;
    } finally {
      button.disabled = false;
    }
  };

  window.addEventListener("message", (e) => {
    if (e.source !== window.parent || !state.graph) return;
    const msg = e.data || {};
    if (msg.type === "lens:inspect") {
      const a = state.graph.accounts.find(
        (x) => x.username.toLowerCase() === String(msg.username).toLowerCase(),
      );
      if (a) inspectAccount(a.id);
    }
    if (msg.type === "lens:anon") setAnonymized(Boolean(msg.value));
    if (msg.type === "lens:replay") replay();
  });

  document.addEventListener("keydown", (e) => {
    if (/INPUT|TEXTAREA|SELECT/.test(e.target.tagName) || $("dialog[open]"))
      return;
    if (e.code === "Space") {
      e.preventDefault();
      replay();
    }
    if (e.key === "Escape") {
      if (state.pick) cancelPick();
      else if (!$("#inspector").hidden) clearFocus();
      else if (document.body.classList.contains("focus")) toggleFocusMode();
      else if (EMBED) window.parent.postMessage({ type: "lens:close" }, "*");
      else setLensVisible(document.body.classList.contains("lens-hidden"));
    }
    if (e.key.toLowerCase() === "f") toggleFocusMode();
    if (e.key.toLowerCase() === "a") setAnonymized(!state.anon);
  });

  // The standalone view uses the actual analyzed conversation as its underlying page.
  // Inside the extension, all layers stay transparent so the real host page shows through.
  function renderSourceContext() {
    if (EMBED || !state.graph) return;
    const g = state.graph;
    const author = state.anon ? "original-author" : "@" + g.post.author;
    const replies = [...g.accounts].sort(
      (a, b) => a.firstReplySeconds - b.firstReplySeconds,
    );
    $("#source-context").innerHTML =
      `<div class="source-shell"><header><span class="source-wordmark">the conversation<span> / </span></span><span>${g.analysis.demo ? "SYNTHETIC SAMPLE" : "IMPORTED CONVERSATION"}</span></header><div class="source-columns"><aside><span>Conversation</span><strong>${g.stats.replies}</strong><p>replies in this thread</p><span class="source-reader-note">A read-only view of the analyzed conversation.</span></aside><div class="source-feed"><div class="feed-title">Thread <span>${g.analysis.demo ? "DEMO" : "IMPORTED"}</span></div><article class="feed-post"><div class="feed-author"><i>${esc(author.replace("@", "").slice(0, 2))}</i><div><strong>${esc(author)}</strong><small>Original post · ${esc(new Date(g.post.createdAt).toLocaleDateString())}</small></div></div><p>${esc(g.post.text)}</p><span>${g.stats.replies} replies · ${g.stats.accounts} accounts</span></article>${replies.map((a) => `<article><div class="feed-author"><i>${esc(displayName(a).replace("@", "").slice(0, 2))}</i><div><strong>${esc(displayName(a))}</strong><small>Reply · +${formatSeconds(a.firstReplySeconds)}</small></div></div><p>${esc(a.replies[0]?.text || "")}</p></article>`).join("")}</div><aside class="source-about"><span>BEYOND THE SURFACE</span><h2>A conversation is more than its words.</h2><p>Open the lens to explore the patterns in text, timing, and connections.</p></aside></div></div>`;
  }
  function setLensVisible(visible) {
    document.body.classList.toggle("lens-hidden", !visible);
    $(".app").inert = !visible;
    $("#source-context").inert = visible;
    $("#activate-lens").hidden = visible;
    if (visible) {
      $("#dismiss-lens").focus();
      resize();
    } else {
      $("#activate-lens").focus();
    }
  }
  $("#dismiss-lens").onclick = () => setLensVisible(false);
  $("#activate-lens").onclick = () => setLensVisible(true);
  // ---------------------------------------------------------------- observatory controls
  function toggleFocusMode() {
    document.body.classList.toggle("focus");
    $("#focus-button").setAttribute(
      "aria-pressed",
      String(document.body.classList.contains("focus")),
    );
  }
  $("#focus-button").onclick = toggleFocusMode;
  $("#anon-button").onclick = () => setAnonymized(!state.anon);
  $("#anon-button").setAttribute("aria-pressed", String(state.anon));
  function zoomBy(factor) {
    if (!cy) return;
    cy.zoom({
      level: clamp(cy.zoom() * factor, 0.4, 3.5),
      renderedPosition: { x: W / 2, y: H / 2 },
    });
  }
  $("#zoom-in").onclick = () => zoomBy(1.2);
  $("#zoom-out").onclick = () => zoomBy(1 / 1.2);
  $("#fit-button").onclick = () => {
    clearFocus(false);
    if (cy) {
      cy.zoom(1);
      cy.pan({ x: 0, y: 0 });
    }
  };
  $("#play-button").onclick = () => {
    if (!state.graph) return;
    if (state.t >= TIMELINE_END) {
      replay();
      return;
    }
    state.playing = !state.playing;
    state.paused = !state.playing;
    updateTimeline();
  };
  $("#timeline-range").oninput = (e) => {
    if (!state.graph) return;
    state.t = Number(e.target.value);
    state.playing = false;
    state.paused = true;
    seedPositions();
    for (let i = 0; i < 180; i++) simulate();
    updateProgress();
    updateHeader();
  };
  function renderAccountList() {
    if (!state.graph) return;
    const query = $("#account-search").value.toLowerCase();
    const items = state.graph.accounts.filter((a) =>
      displayName(a).toLowerCase().includes(query),
    );
    $("#account-list").innerHTML =
      items
        .map(
          (a) =>
            `<button data-explore="${esc(a.id)}"><i class="swatch ${TONE_CLASS[visualClass(a)]}"></i>${esc(displayName(a))}<small>${LABEL[visualClass(a)]}</small></button>`,
        )
        .join("") || '<p class="empty-search">No matching accounts.</p>';
    $$("[data-explore]").forEach(
      (b) =>
        (b.onclick = () => {
          $("#explore-dialog").close();
          if (state.pick) pickForPath(b.dataset.explore);
          else inspectAccount(b.dataset.explore);
        }),
    );
  }
  $("#explore-button").onclick = () => {
    renderAccountList();
    $("#explore-dialog").showModal();
  };
  $("#explore-close").onclick = () => $("#explore-dialog").close();
  $("#account-search").oninput = renderAccountList;
  $("#snapshot-button").onclick = () => {
    if (!cy || !state.graph) return;
    const canvas = document.createElement("canvas");
    canvas.width = 1600;
    canvas.height = 1000;
    const ctx = canvas.getContext("2d");
    ctx.fillStyle = "#0c0c0d";
    ctx.fillRect(0, 0, 1600, 1000);
    ctx.fillStyle = COLOR.accent;
    ctx.font = "13px 'IBM Plex Mono'";
    ctx.fillText("DEAD INTERNET LENS  /  NETWORK OBSERVATORY", 60, 56);
    ctx.fillStyle = COLOR.text;
    ctx.font = "42px 'Space Grotesk'";
    ctx.fillText("Beneath the conversation.", 60, 119);
    const image = new Image();
    image.onload = () => {
      const scale = Math.min(1480 / W, 700 / H),
        x = (1600 - W * scale) / 2,
        y = 165;
      ctx.drawImage(underlay, x, y, W * scale, H * scale);
      ctx.drawImage(image, x, y, W * scale, H * scale);
      ctx.drawImage(overlay, x, y, W * scale, H * scale);
      ctx.font = "12px 'IBM Plex Mono'";
      ctx.fillStyle = "#93959f";
      ctx.fillText($("#network-stats").textContent, 60, 928);
      ctx.fillText(
        $("#source-status").textContent +
          " · Experimental heuristic — not ground truth.",
        60,
        958,
      );
      canvas.toBlob((blob) => {
        if (!blob) return;
        const link = document.createElement("a"),
          url = URL.createObjectURL(blob);
        link.href = url;
        link.download = "dead-internet-lens.png";
        link.click();
        setTimeout(() => URL.revokeObjectURL(url), 5000);
      });
    };
    image.src = cy.png({ output: "base64uri", scale: dpr });
  };

  // ---------------------------------------------------------------- live capture of a real X / LinkedIn post
  // The server drives a real browser window: it opens the post, scrolls the thread, reads the replies, analyzes
  // them and badges them in the page. Screenshots stream into this view; the graph reveal follows.
  const capture = { id: null, url: null, frame: -1, timer: null };
  // The live view covers the whole window, so it lives at the top level rather than inside the graph stage.
  document.body.appendChild($("#capture-view"));

  async function startCapture(url) {
    clearTimeout(capture.timer);
    const view = $("#capture-view");
    view.hidden = false;
    view.classList.remove("leaving");
    $("#capture-frame").removeAttribute("src");
    renderCapture({ stage: "STARTING", site: "", collected: 0, message: "Opening the post…" });
    try {
      const status = await api("/api/captures", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ url }),
      });
      Object.assign(capture, { id: status.id, url, frame: -1 });
      pollCapture();
    } catch (e) {
      renderCapture({ stage: "FAILED", site: "", collected: 0, message: e.message });
    }
  }

  async function pollCapture() {
    let status;
    try {
      status = await api(`/api/captures/${capture.id}`);
    } catch (e) {
      renderCapture({ stage: "FAILED", site: "", collected: 0, message: e.message });
      return;
    }
    renderCapture(status);
    if (status.frame !== capture.frame) {
      capture.frame = status.frame;
      // Swap frames only once loaded, so the page never flickers.
      const next = new Image();
      next.onload = () => ($("#capture-frame").src = next.src);
      next.src = `/api/captures/${capture.id}/frame?v=${status.frame}`;
    }
    if (status.stage === "COMPLETE") {
      capture.timer = setTimeout(async () => {
        $("#capture-view").classList.add("leaving");
        try {
          await load(await api(`/api/analyses/${status.analysisId}`));
        } catch (e) {
          showBanner(e.message, 6000, false, true);
        }
        setTimeout(() => ($("#capture-view").hidden = true), 700);
      }, 2600); // hold on the badged page for a moment before the graph takes over
    } else if (status.stage !== "FAILED" && status.stage !== "NEEDS_LOGIN") {
      capture.timer = setTimeout(pollCapture, 300);
    }
  }

  function renderCapture(status) {
    const view = $("#capture-view");
    view.dataset.stage = status.stage;
    const linkedIn = status.site === "LinkedIn";
    $("#capture-site").textContent = status.site ? `LIVE · ${status.site}` : "LIVE";
    $("#capture-count").textContent = fmt(status.collected || 0);
    const feed = /\/(home|feed\/?)$/.test(capture.url || "");
    $("#capture-noun").textContent = `${feed ? "posts" : linkedIn ? "comments" : "replies"} collected${status.skipped ? ` · ${status.skipped} media-only skipped` : ""}`;
    $("#capture-message").textContent = status.message || "";
    const busy = ["STARTING", "LOADING", "SCROLLING"].includes(status.stage);
    $("#capture-stop").hidden = status.stage !== "SCROLLING";
    $("#capture-show").hidden = !busy;
    $("#capture-signin").hidden = status.stage !== "NEEDS_LOGIN" || capture.signingIn;
    $("#capture-continue").hidden = status.stage !== "NEEDS_LOGIN";
    capture.site = status.site || capture.site;
    $("#capture-close").hidden = !(status.stage === "FAILED" || status.stage === "NEEDS_LOGIN");
    $("#capture-submit").disabled = busy || status.stage === "ANALYZING";
  }

  $$("[data-feed]").forEach((b) => (b.onclick = () => startCapture(b.dataset.feed)));
  // Hosted deployments have no logged-in browser to drive, so live capture exists only in the local app.
  api("/api/health")
    .then((health) => {
      if (health.capture) return;
      $("#capture-form").hidden = true;
      $$("[data-feed]").forEach((b) => (b.hidden = true));
    })
    .catch(() => {});
  $("#capture-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const url = $("#capture-url").value.trim();
    if (url) startCapture(url);
  });
  $("#capture-stop").onclick = async () => {
    if (capture.id) await api(`/api/captures/${capture.id}/stop`, { method: "POST" }).catch(() => {});
  };
  $("#capture-show").onclick = () => api("/api/browser/show", { method: "POST" }).catch(() => {});
  $("#capture-continue").onclick = () => capture.url && startCapture(capture.url);
  // Sites refuse sign-in from the automated window, so sign-in happens in a plain Chrome window on the same profile.
  // Closing that window means the user is done; the capture then resumes by itself.
  $("#capture-signin").onclick = async () => {
    try {
      await api("/api/browser/sign-in", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ site: capture.site || "X" }),
      });
    } catch (e) {
      $("#capture-message").textContent = e.message;
      return;
    }
    capture.signingIn = true;
    $("#capture-signin").hidden = true;
    $("#capture-message").textContent = `A Chrome window opened on the ${capture.site || "X"} login page. Sign in there, then close that window and Lens continues.`;
    const waitForClose = async () => {
      const { open } = await api("/api/browser/sign-in").catch(() => ({ open: false }));
      if (open) {
        capture.timer = setTimeout(waitForClose, 1000);
        return;
      }
      capture.signingIn = false;
      startCapture(capture.url);
    };
    capture.timer = setTimeout(waitForClose, 1500);
  };
  $("#capture-close").onclick = () => {
    clearTimeout(capture.timer);
    $("#capture-view").hidden = true;
    $("#capture-submit").disabled = false;
  };

  // ---------------------------------------------------------------- start
  (async () => {
    try {
      const id = new URLSearchParams(location.search).get("analysis");
      const status = id
        ? await api(`/api/analyses/${encodeURIComponent(id)}`).catch(() =>
            api("/api/demo"),
          )
        : await api("/api/demo");
      await load(status);
    } catch (e) {
      $("#progress-title").textContent = "Could not load the analysis";
      showBanner(e.message, 0, false, true);
    }
  })();
})();
