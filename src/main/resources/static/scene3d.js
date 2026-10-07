import * as THREE from './vendor/three.module.js';
import { OrbitControls } from './vendor/OrbitControls.js';

// Spatial position is a presentation choice. Only server-provided edges are evidence.
const seed = (s) => { let n = 2166136261; for (const c of s) n = Math.imul(n ^ c.charCodeAt(0), 16777619); return (n >>> 0) / 4294967295; };
// Classification colors come from the theme (theme.css), so 2D and 3D always match.
const themeColor = (name, fallback) => getComputedStyle(document.documentElement).getPropertyValue(name).trim() || fallback;
const colors = { human_like: themeColor('--human', '#7cc4ff'), uncertain: themeColor('--uncertain', '#f0c674'), automation_like: themeColor('--automation', '#ff4f6d') };
export class LensScene3D {
  constructor(host, hooks) {
    this.host = host;
    this.hooks = hooks;
    this.renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true, preserveDrawingBuffer: true });
    this.renderer.setPixelRatio(Math.min(devicePixelRatio, 2));
    this.renderer.setClearColor(0x061016, 0);
    this.renderer.domElement.setAttribute('aria-label', '3D signal map. Drag to orbit, scroll to zoom. Explore accounts provides keyboard access.');
    this.renderer.domElement.setAttribute('role', 'img');
    host.appendChild(this.renderer.domElement);
    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(42, 1, 1, 3000);
    this.controls = new OrbitControls(this.camera, this.renderer.domElement);
    this.controls.enableDamping = !matchMedia('(prefers-reduced-motion: reduce)').matches;
    this.controls.dampingFactor = .08;
    this.controls.minDistance = 210;
    this.controls.maxDistance = 1300;
    this.controls.enablePan = false;
    this.scene.add(new THREE.AmbientLight(0xb7e4f1, 2));
    const light = new THREE.DirectionalLight(0xffffff, 3);
    light.position.set(-100, 200, 350); this.scene.add(light);
    this.content = new THREE.Group(); this.scene.add(this.content);
    this.sphere = new THREE.SphereGeometry(1, 16, 12);
    this.diamond = new THREE.OctahedronGeometry(1);
    this.ray = new THREE.Raycaster(); this.pointer = new THREE.Vector2();
    this.nodes = new Map(); this.edges = []; this.labels = [];
    this.reset();
    this.observer = new ResizeObserver(() => this.resize()); this.observer.observe(host);
    const canvas = this.renderer.domElement;
    canvas.addEventListener('webglcontextlost', (e) => { e.preventDefault(); hooks.failed(); });
    canvas.addEventListener('pointerdown', (e) => { this.down = [e.clientX, e.clientY]; });
    canvas.addEventListener('pointerup', (e) => {
      if (this.down && Math.hypot(e.clientX - this.down[0], e.clientY - this.down[1]) < 5) {
        const id = this.hit(e); if (id) hooks.select(id); else hooks.clear();
      }
      this.down = null;
    });
    canvas.addEventListener('pointermove', (e) => {
      const id = this.hit(e); canvas.style.cursor = id ? 'pointer' : 'grab';
      this.hover = id;
      const rect = host.getBoundingClientRect(); hooks.hover(id, e.clientX - rect.left, e.clientY - rect.top);
    });
    canvas.addEventListener('pointerleave', () => { this.hover = null; hooks.hover(null); });
  }
  hit(e) {
    const r = this.host.getBoundingClientRect();
    this.pointer.set((e.clientX - r.left) / r.width * 2 - 1, 1 - (e.clientY - r.top) / r.height * 2);
    this.ray.setFromCamera(this.pointer, this.camera);
    return this.ray.intersectObjects([...this.nodes.values()].map(n => n.mesh)).find(x => x.object.visible && x.object.material.opacity > .2)?.object.userData.id;
  }
  reset() { this.camera.position.set(0, 65, 560); this.controls.target.set(0, 0, 0); this.controls.update(); }
  zoom(factor) { this.camera.position.sub(this.controls.target).divideScalar(factor).add(this.controls.target); this.controls.update(); }
  resize() {
    const { width, height } = this.host.getBoundingClientRect(); if (!width || !height) return;
    this.renderer.setSize(width, height); this.camera.aspect = width / height; this.camera.updateProjectionMatrix();
  }
  load(graph) {
    this.content.traverse(o => { if (o.material) o.material.dispose(); if (o.geometry && o.geometry !== this.sphere && o.geometry !== this.diamond) o.geometry.dispose(); });
    this.content.clear(); this.nodes.clear(); this.edges = []; this.labels = [];
    this.feed = graph.analysis.kind === 'feed'; this.reset();
    const grouped = graph.clusters;
    const centers = new Map(grouped.map((c, i) => {
      const angle = i / Math.max(1, grouped.length) * Math.PI * 2 - .6;
      return [c.id, new THREE.Vector3(Math.cos(angle) * 220, Math.sin(angle) * 85, Math.sin(angle * 2) * 48)];
    }));
    const members = new Map();
    graph.accounts.forEach(a => { const key = a.clusterId || 'loose'; if (!members.has(key)) members.set(key, []); members.get(key).push(a); });
    graph.accounts.forEach((a, i) => {
      const center = centers.get(a.clusterId);
      const siblings = members.get(a.clusterId || 'loose');
      const j = siblings.indexOf(a), n = siblings.length;
      const angle = j * Math.PI * (3 - Math.sqrt(5));
      const y = 1 - 2 * (j + .5) / n, radius = Math.sqrt(1 - y * y);
      const size = center ? 20 + Math.sqrt(n) * 7 : 175;
      const target = new THREE.Vector3(Math.cos(angle) * radius * size, y * size * (center ? .8 : .65), Math.sin(angle) * radius * size * .6);
      if (!center) target.x *= 1.5;
      if (center) target.add(center);
      const start = new THREE.Vector3(Math.cos(i * 2.399) * 240, Math.sin(i * 2.399) * 105, (seed(a.id) - .5) * 160);
      const material = new THREE.MeshStandardMaterial({ color: 0xb5c8d4, roughness: .3, metalness: .3, emissive: 0x486777, emissiveIntensity: .35, transparent: true });
      const mesh = new THREE.Mesh(this.sphere, material); mesh.userData.id = a.id;
      this.content.add(mesh); this.nodes.set(a.id, { mesh, target, start, account: a, rank: i / graph.accounts.length });
    });
    this.root = null;
    if (!this.feed) {
      this.root = new THREE.Mesh(this.diamond, new THREE.MeshStandardMaterial({color:0xe6fff4,emissive:0x70e2c2,emissiveIntensity:.6}));
      this.root.scale.setScalar(7); this.content.add(this.root);
    }
    const evidence = this.feed ? [] : [...graph.edges, ...graph.accounts.map(a => ({source:null,target:a.id,type:'REPLY'}))];
    evidence.forEach(e => {
      if ((e.source && !this.nodes.has(e.source)) || !this.nodes.has(e.target)) return;
      const geometry = new THREE.BufferGeometry(); geometry.setAttribute('position', new THREE.BufferAttribute(new Float32Array(6), 3));
      const line = new THREE.Line(geometry, new THREE.LineBasicMaterial({ color: e.type === 'REPLY' ? 0x99b8c3 : e.type === 'SIMILAR' ? 0x8cbbce : 0xff8594, transparent: true, depthWrite: false }));
      this.content.add(line); this.edges.push({ line, data: e });
    });
    // Reference orbits are deliberately separate from evidence links.
    [205, 240].forEach((r, i) => {
      const points = Array.from({ length: 129 }, (_, j) => { const a = j / 128 * Math.PI * 2; return new THREE.Vector3(Math.cos(a) * r, 0, Math.sin(a) * r); });
      const ring = new THREE.Line(new THREE.BufferGeometry().setFromPoints(points), new THREE.LineBasicMaterial({ color: 0x7cb1ba, transparent: true, opacity: .12 }));
      ring.scale.x = 1.5; ring.rotation.x = .3 + i * .65; ring.rotation.z = -.15; this.content.add(ring);
    });
    grouped.forEach(c => {
      const pos = centers.get(c.id).clone(); pos.y += 32 + Math.sqrt(c.accountIds.length) * 7;
      const element = [...document.querySelectorAll('#cluster-labels [data-cluster]')].find(b => b.dataset.cluster === c.id);
      if (element) this.labels.push({ pos, element, ids: c.accountIds });
    });
    this.resize();
  }
  render(view) {
    const reveal = view.phase('discover'), arrange = view.phase('compare'), classify = view.phase('classify');
    this.nodes.forEach(n => {
      const active = view.active(n.account) && (!view.path || view.path.has(n.account.id)), kind = view.kind(n.account);
      n.mesh.visible = reveal >= n.rank;
      n.mesh.position.copy(n.start).lerp(n.target, arrange);
      n.mesh.geometry = classify > .5 && kind === 'automation_like' ? this.diamond : this.sphere;
      n.mesh.material.color.set(classify > n.rank * .6 ? colors[kind] : '#b8b0c1');
      n.mesh.material.emissive.copy(n.mesh.material.color);
      n.mesh.material.opacity = active ? 1 : .08;
      const selected = view.selected === n.account.id || this.hover === n.account.id;
      n.mesh.material.emissiveIntensity = selected ? .8 : .22;
      n.mesh.scale.setScalar((selected ? 7 : 3.5) + Math.min(2, Math.sqrt(n.account.replyCount || 1) * .45));
    });
    this.edges.forEach(({ line, data }) => {
      const a = data.source ? this.nodes.get(data.source) : {mesh:this.root}, b = this.nodes.get(data.target);
      const alpha = view.phase(data.type === 'REPLY' ? 'discover' : data.type === 'SIMILAR' ? 'similar' : 'coordinate');
      line.visible = a.mesh.visible && b.mesh.visible && alpha > 0;
      line.geometry.attributes.position.setXYZ(0, ...a.mesh.position.toArray());
      line.geometry.attributes.position.setXYZ(1, ...b.mesh.position.toArray());
      line.geometry.attributes.position.needsUpdate = true;
      const active = (!a.account || view.active(a.account)) && view.active(b.account);
      const onPath = view.path && view.path.has(data.source) && view.path.has(data.target);
      line.material.opacity = alpha * (view.path ? onPath ? .9 : .015 : active ? data.type === 'REPLY' ? .045 : .35 : .015);
    });
    this.controls.update();
    const w = this.host.clientWidth, h = this.host.clientHeight;
    this.labels.forEach(({ pos, element, ids }) => {
      const p = pos.clone().project(this.camera);
      element.style.left = `${(p.x + 1) * w / 2}px`; element.style.top = `${(1 - p.y) * h / 2}px`;
      element.style.opacity = view.phase('cluster') * (ids.some(id => view.active(this.nodes.get(id).account)) ? 1 : .15);
      element.style.visibility = p.z > 1 || Math.abs(p.x) > .95 || Math.abs(p.y) > .9 ? 'hidden' : 'visible';
      element.style.pointerEvents = view.phase('cluster') > .8 ? 'auto' : 'none';
    });
    this.renderer.render(this.scene, this.camera);
  }
  snapshot() { return this.renderer.domElement.toDataURL('image/png'); }
}
