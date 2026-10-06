"""Builds X-like and LinkedIn-like thread pages from the demo conversation for testing the extension without logging in.

The markup mimics what content.js reads on the real sites. When a site changes its markup, update both content.js
and these fixtures. Run: python3 build_fixture.py && python3 serve.py, then open the URLs serve.py prints.
"""
import json, html, random, pathlib
HERE = pathlib.Path(__file__).resolve().parent
from datetime import datetime, timedelta
conv = json.load(open(HERE.parent.parent / 'src/main/resources/demo-conversation.json'))
random.seed(1)  # fixed, so the LinkedIn post URL is stable
def article(handle, tid, text, created, quote=None, media_only=False, promoted=False):
    parts = [f'<article data-testid="tweet" role="article"><div class="row"><div data-testid="User-Name"><span class="name">{html.escape(handle.title())}</span> <a href="/{handle}">@{html.escape(handle)}</a>']
    if not promoted:
        parts.append(f' · <a href="/{handle}/status/{tid}"><time datetime="{created}">now</time></a>')
    parts.append('</div></div>')
    if not media_only:
        parts.append(f'<div data-testid="tweetText">{html.escape(text)}</div>')
    else:
        parts.append('<div class="media">[image]</div>')
    if quote:
        parts.append(f'<div role="link" class="quote"><div data-testid="tweetText">{html.escape(quote)}</div></div>')
    parts.append('</article>')
    return ''.join(parts)
post = conv['post']
rows = [article('someone_earlier', '999', 'An earlier post this is replying to.', '2026-10-06T09:00:00.000Z')]
rows.append(article(post['author'], '1000', post['text'], post['createdAt'].replace('Z', '.000Z')))
rows.append(article('ad_account', '0', 'Buy our product', '', promoted=True))
replies = conv['replies']
visible, hidden = replies[:70], replies[70:]
for i, r in enumerate(visible):
    rows.append(article(r['author']['username'], r['id'].replace('reply_', '2'), r['text'], r['createdAt'].replace('Z', '.000Z'),
                        quote='A quoted tweet that must be ignored.' if i == 3 else None))
rows.append(article('photo_person', '3001', '', '2026-10-06T10:20:00.000Z', media_only=True))
hidden_html = ''.join(article(r['author']['username'], r['id'].replace('reply_', '2'), r['text'], r['createdAt'].replace('Z', '.000Z')) for r in hidden)
page = f'''<!doctype html><html data-dil-platform="x"><head><meta charset="utf-8"><title>Post / X (test fixture)</title>
<link rel="stylesheet" href="/ext/content.css">
<style>body{{background:#000;color:#e7e9ea;font:15px system-ui;margin:0}} main{{display:flex;justify-content:center}}
[data-testid=primaryColumn]{{width:600px;border-left:1px solid #2f3336;border-right:1px solid #2f3336}}
article{{padding:12px 16px;border-bottom:1px solid #2f3336;display:block}} .name{{font-weight:700}} a{{color:#71767b;text-decoration:none}}
.quote{{border:1px solid #2f3336;border-radius:12px;padding:8px;margin-top:8px;color:#71767b}} [role=button]{{display:block;padding:16px;color:#1d9bf0;cursor:pointer}}</style>
<script>window.chrome={{runtime:{{getURL:p=>location.origin+'/ext/'+p+'?server='+encodeURIComponent(location.origin),onMessage:{{addListener(){{}}}}}}}};</script>
</head><body><main><div data-testid="primaryColumn">{''.join(rows)}
<div role="button" id="spam">Show probable spam</div><div id="hidden-replies"></div></div></main>
<script>document.getElementById('spam').addEventListener('click',e=>{{document.getElementById('hidden-replies').innerHTML={json.dumps(hidden_html)};e.target.remove();}});</script>
<script src="/ext/collector.js"></script><script src="/ext/content.js"></script></body></html>'''
open(HERE / 'x.html', 'w').write(page)
print(len(visible), 'visible +', len(hidden), 'behind "Show probable spam"')

# ---------------- LinkedIn-like fixture
from datetime import timezone
def snowflake(iso):
    ms = int(datetime.fromisoformat(iso.replace('Z', '+00:00')).timestamp() * 1000)
    return str((ms << 22) | random.randint(0, (1 << 22) - 1))
post_id = snowflake(post['createdAt'])
def comment(r, nested_html='', company=False):
    cid = snowflake(r['createdAt'])
    slug = r['author']['username'].replace('_', '-') + '-' + format(sum(map(ord, r['author']['id'])) * 2654435761 % 0xffff, 'x')
    href = f'/company/{slug}/' if company else f'/in/{slug}/'
    return (f'<article class="comments-comment-entity" data-id="urn:li:comment:(activity:{post_id},{cid})">'
            f'<a class="comments-comment-meta__image-link" href="https://www.linkedin.com{href}"><img alt=""></a>'
            f'<div class="comments-comment-meta__description-container"><span class="comments-comment-meta__description-title">{html.escape(r["author"]["username"].replace("_", " ").title())}</span></div>'
            f'<time class="comments-comment-meta__data">2h</time>'
            f'<div class="comments-comment-item__main-content"><span dir="ltr">{html.escape(r["text"])}</span></div>'
            f'{nested_html}</article>')
li_replies = conv['replies']
first, more = li_replies[:60], li_replies[60:]
nested = comment(first[1])
items = [comment(first[0], nested_html='<div class="replies">' + nested + '</div>')]
items += [comment(r, company=(i == 5)) for i, r in enumerate(first[2:])]
more_html = ''.join(comment(r) for r in more)
li_page = f'''<!doctype html><html data-dil-platform="linkedin"><head><meta charset="utf-8"><title>LinkedIn post (test fixture)</title>
<link rel="stylesheet" href="/ext/content.css">
<style>body{{background:#f4f2ee;color:#191919;font:14px system-ui;margin:0}} main{{width:560px;margin:20px auto;background:#fff;border-radius:8px;padding:12px 16px}}
article{{display:block;padding:10px 0 10px 8px;border-top:1px solid #eee}} .replies{{margin-left:36px}} .comments-comment-meta__description-title{{font-weight:600}}
time{{color:#666;font-size:12px;margin-left:6px}} .update-components-actor__name{{font-weight:600}} button{{margin:10px 0}}</style>
<script>window.chrome={{runtime:{{getURL:p=>location.origin+'/ext/'+p+'?server='+encodeURIComponent(location.origin),onMessage:{{addListener(){{}}}}}}}};</script>
</head><body><main>
<div class="feed-shared-update-v2" data-urn="urn:li:activity:{post_id}">
<div class="update-components-actor__container"><a class="update-components-actor__meta-link" href="https://www.linkedin.com/in/alex-chen-0a1b2c/"><span class="update-components-actor__name">Alex Chen</span></a></div>
<div class="update-components-text"><span dir="ltr">{html.escape(post['text'])}</span></div>
<div class="comments-comments-list">{''.join(items)}<div id="more"></div>
<button class="comments-comments-list__load-more-comments-button" id="load">Load more comments</button></div>
</div></main>
<script>document.getElementById('load').addEventListener('click',e=>{{document.getElementById('more').innerHTML={json.dumps(more_html)};e.target.remove();}});</script>
<script src="/ext/collector.js"></script><script src="/ext/content.js"></script></body></html>'''
open(HERE / 'linkedin.html', 'w').write(li_page)
print('linkedin:', len(first), 'visible +', len(more), 'behind "Load more comments"; post', post_id)

# ---------------- Home feeds: every demo reply becomes a feed post by its author, in shuffled order, and more
# posts load as you scroll (like the real infinite feeds). A promoted post is mixed in and must be skipped.
feed = conv['replies'][:]
random.shuffle(feed)
first_feed, later_feed = feed[:40], feed[40:]
infinite = '''<script>
const later = %s; let loaded = 0;
addEventListener('scroll', () => {
  if (loaded >= later.length || innerHeight + scrollY < document.body.scrollHeight - 400) return;
  const chunk = later.slice(loaded, loaded + 12); loaded += chunk.length;
  document.getElementById('feed-more').insertAdjacentHTML('beforeend', chunk.join(''));
});
</script>'''
x_items = [article(r['author']['username'], r['id'].replace('reply_', '5'), r['text'], r['createdAt'].replace('Z', '.000Z')) for r in first_feed]
x_items.insert(5, article('brand_account', '0', 'Try our new app today', '', promoted=True))
x_later = [article(r['author']['username'], r['id'].replace('reply_', '5'), r['text'], r['createdAt'].replace('Z', '.000Z')) for r in later_feed]
x_feed = page.split('<main>')[0].replace('Post / X (test fixture)', 'Home / X (test fixture)') + (
    '<main><div data-testid="primaryColumn">' + ''.join(x_items) + '<div id="feed-more"></div></div></main>'
    + infinite % json.dumps(x_later) + '<script src="/ext/collector.js"></script><script src="/ext/content.js"></script></body></html>')
open(HERE / 'x_feed.html', 'w').write(x_feed)

def li_card(r):
    aid = snowflake(r['createdAt'])
    slug = r['author']['username'].replace('_', '-') + '-' + format(sum(map(ord, r['author']['id'])) * 2654435761 % 0xffff, 'x')
    return (f'<div class="feed-shared-update-v2" data-urn="urn:li:activity:{aid}">'
            f'<div class="update-components-actor__container"><a class="update-components-actor__meta-link" href="https://www.linkedin.com/in/{slug}/">'
            f'<span class="update-components-actor__title">{html.escape(r["author"]["username"].replace("_", " ").title())}</span></a></div>'
            f'<div class="update-components-text"><span dir="ltr">{html.escape(r["text"])}</span></div></div>')
li_promo = ('<div class="feed-shared-update-v2" data-urn="urn:li:sponsored:1"><div class="update-components-actor__container">'
            '<span class="update-components-actor__title">Acme Corp · Promoted</span></div><div class="update-components-text">Grow faster</div></div>')
li_feed = li_page.split('<main>')[0].replace('LinkedIn post (test fixture)', 'Feed | LinkedIn (test fixture)') + (
    '<main>' + li_promo + ''.join(li_card(r) for r in first_feed) + '<div id="feed-more"></div></main>'
    + infinite % json.dumps([li_card(r) for r in later_feed]) + '<script src="/ext/collector.js"></script><script src="/ext/content.js"></script></body></html>')
open(HERE / 'linkedin_feed.html', 'w').write(li_feed)
print('feeds:', len(first_feed), 'posts loaded +', len(later_feed), 'on scroll (X and LinkedIn)')
