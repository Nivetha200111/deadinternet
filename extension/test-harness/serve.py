"""Test harness: serves the fixtures at X-/LinkedIn-like URLs with a stub of the chrome extension API, the extension
files at /ext/, and proxies everything else to the Lens server on :8080, so the whole overlay runs in a plain tab.
"""
import http.server, urllib.request, pathlib, re, os
HERE = pathlib.Path(__file__).resolve().parent
EXT = HERE.parent
# LENS_SERVER points the proxy at another Lens server, e.g. a second instance running with the local heuristic.
LENS = os.environ.get('LENS_SERVER', 'http://localhost:8080')
FIXTURE = HERE / 'x.html'
LINKEDIN = HERE / 'linkedin.html'
class Handler(http.server.BaseHTTPRequestHandler):
    def _send(self, code, body, ctype):
        self.send_response(code); self.send_header('Content-Type', ctype); self.send_header('Content-Length', str(len(body))); self.end_headers(); self.wfile.write(body)
    def _proxy(self):
        length = int(self.headers.get('Content-Length') or 0)
        data = self.rfile.read(length) if length else None
        req = urllib.request.Request(LENS + self.path, data=data, method=self.command,
                                     headers={'Content-Type': self.headers.get('Content-Type', 'application/json')})
        try:
            with urllib.request.urlopen(req) as r: self._send(r.status, r.read(), r.headers.get('Content-Type', 'text/plain'))
        except urllib.error.HTTPError as e: self._send(e.code, e.read(), e.headers.get('Content-Type', 'text/plain'))
    def do_GET(self):
        path = self.path.split('?')[0]
        if path == '/alexchen/status/1000': return self._send(200, FIXTURE.read_bytes(), 'text/html')
        if path == '/home': return self._send(200, (HERE / 'x_feed.html').read_bytes(), 'text/html')
        if path == '/feed/': return self._send(200, (HERE / 'linkedin_feed.html').read_bytes(), 'text/html')
        if path.startswith('/feed/update/urn:li:activity:'): return self._send(200, LINKEDIN.read_bytes(), 'text/html')
        if path.startswith('/ext/'):
            f = EXT / path[5:]
            ctype = {'.js': 'text/javascript', '.css': 'text/css', '.html': 'text/html'}.get(f.suffix, 'text/plain')
            return self._send(200, f.read_bytes(), ctype) if f.is_file() else self._send(404, b'', 'text/plain')
        self._proxy()
    def do_POST(self): self._proxy()
    def log_message(self, *a): pass
post_id = re.search(r'urn:li:activity:(\d+)', LINKEDIN.read_text()).group(1)
print('X fixture:        http://127.0.0.1:8765/alexchen/status/1000', flush=True)
print('X feed:           http://127.0.0.1:8765/home', flush=True)
print('LinkedIn feed:    http://127.0.0.1:8765/feed/', flush=True)
print(f'LinkedIn fixture: http://127.0.0.1:8765/feed/update/urn:li:activity:{post_id}/', flush=True)
http.server.ThreadingHTTPServer(('127.0.0.1', 8765), Handler).serve_forever()
