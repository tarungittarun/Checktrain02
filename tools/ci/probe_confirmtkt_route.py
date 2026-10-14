#!/usr/bin/env python3
"""Short CI capture of dated route and actual/estimate labels; read-only, not a source claim."""
import html
from html.parser import HTMLParser
import re
import time
import urllib.request


class Visible(HTMLParser):
    def __init__(self):
        super().__init__()
        self.skip = 0
        self.parts = []

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'):
            self.skip += 1

    def handle_endtag(self, tag):
        if tag in ('script', 'style'):
            self.skip = max(0, self.skip - 1)

    def handle_data(self, data):
        if not self.skip:
            self.parts.append(data)


def probe(number):
    url = 'https://www.confirmtkt.com/train-running-status/' + number
    req = urllib.request.Request(url, headers={
        'User-Agent': 'Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 Chrome/120.0 Safari/537.36',
        'Accept': 'text/html',
    })
    start = time.monotonic()
    try:
        with urllib.request.urlopen(req, timeout=8) as r:
            raw = r.read(180_000).decode('utf-8', 'replace')
            print(f'{number}: HTTP {r.status}, {len(raw)} chars in {time.monotonic()-start:.1f}s')
    except Exception as exc:
        print(f'{number}: {type(exc).__name__} {str(exc)[:100]} in {time.monotonic()-start:.1f}s')
        return
    parser = Visible()
    parser.feed(raw)
    visible = re.sub(r'\s+', ' ', html.unescape(' '.join(parser.parts)))
    for word in ('Day 1', 'Station', 'Delay by', 'Actual Arrival', 'Estimated Arrival'):
        m = re.search(re.escape(word), visible, flags=re.I)
        if m:
            print(f'{number} visible around {word}: {visible[max(0,m.start()-90):m.start()+2100][:2200]}')
    for word in ('stationCode', 'stnCode', 'trainRoute', 'actualArrival', 'actualDeparture', 'isHalt', 'runningStatus'):
        hits = list(re.finditer(re.escape(word), raw, flags=re.I))
        if hits:
            print(f'{number} raw {word} count={len(hits)} contexts: ' +
                  ' | '.join(re.sub(r'\s+', ' ', raw[max(0,h.start()-85):h.start()+160])
                           for h in hits[:2]))


if __name__ == '__main__':
    print('ConfirmTkt route/live-label trial. A page response is NOT proof of an actual time.')
    for no in ('20689', '20652'):
        probe(no)
