#!/usr/bin/env python3
"""Bounded read-only trial. A RailYatri page may call FUTURE projections 'Departed at'.

Do not parse it into an app actual until the raw event schema differentiates observations
from estimates and the page proves the selected train/run. Prints only tiny HTML contexts.
"""
import html
from html.parser import HTMLParser
import re
import time
import urllib.error
import urllib.request


class Visible(HTMLParser):
    def __init__(self):
        super().__init__()
        self.parts = []
        self.skip = 0

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'):
            self.skip += 1

    def handle_endtag(self, tag):
        if tag in ('script', 'style'):
            self.skip = max(0, self.skip - 1)

    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)


def probe(number, query=''):
    url = f'https://www.railyatri.in/live-train-status/{number}{query}'
    req = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120 Safari/537.36',
        'Accept': 'text/html,application/xhtml+xml',
    })
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=7) as r:
            raw = r.read(350_000).decode('utf-8', 'replace')
            print(f'{number} {query or "default"}: HTTP {r.status}, {len(raw)} chars in {time.monotonic()-start:.1f}s', flush=True)
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        print(f'{number} {query or "default"}: {type(exc).__name__}: {str(exc)[:120]} in {time.monotonic()-start:.1f}s', flush=True)
        return
    p = Visible()
    p.feed(raw)
    text = re.sub(r'\s+', ' ', html.unescape(' '.join(p.parts)))
    for phrase in ('Start Date', 'Last Reported', 'As of', 'MALLESWARAM', 'YESVANTPUR', 'KSR BENGALURU', 'KADUR'):
        m = re.search(re.escape(phrase), text, re.I)
        if m:
            print(f'  visible around {phrase}: {text[max(0,m.start()-75):m.start()+490]}', flush=True)
    for phrase in ('actualArrival', 'actualDeparture', 'actual_arrival', 'arrival_status', 'estimatedArrival',
                   'lastReported', 'last_reported', 'statusData', 'trainStatus', 'isActual', 'eventType'):
        hits = list(re.finditer(re.escape(phrase), raw, re.I))
        if hits:
            print(f'  raw {phrase} {len(hits)} hits: ' + ' | '.join(
                re.sub(r'\s+', ' ', raw[max(0,m.start()-100):m.start()+220]) for m in hits[:2])[:750], flush=True)
    for code in ('YPR', 'MWM', 'SBC'):
        # Prefer a row near the status table instead of a JavaScript autocomplete item.
        pattern = rf'<tr[^>]*>[^<]*?(?:<[^>]*>[^<]*){{0,18}}?\b{code}\b[\s\S]*?</tr>'
        m = re.search(pattern, raw, re.I)
        if m:
            snippet = re.sub(r'\s+', ' ', m.group(0))[:1200]
            print(f'  {code} tr sample: {snippet}', flush=True)
    print(f'  raw prefix={raw[:100]!r}', flush=True)


if __name__ == '__main__':
    print('RailYatri trial. A future row can say "Departed at": it is NOT an observed event on that basis.', flush=True)
    probe('20652')
    probe('20689')
    probe('20652', '?start_day=1&start_date=20261013')
