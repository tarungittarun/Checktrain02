#!/usr/bin/env python3
"""One-off bounded public GET of an undocumented Where is My Train endpoint.

Research only. No app integration without a stable authorized API and a way to
separate observed same-run arrival/departure events from computed predictions.
"""
import json
import time
import urllib.error
import urllib.parse
import urllib.request


def probe(number, start, end):
    params = urllib.parse.urlencode({
        'date': '14-10-2026', 'train_no': number,
        'from': start, 'to': end, 'from_day': '1', 'lang': 'en',
        'appVersion': '7.1.5.633808517', 'sb_version': '0',
    })
    url = 'https://whereismytrain.in/cache/live_status?' + params
    began = time.monotonic()
    req = urllib.request.Request(url, headers={'Accept': 'application/json',
                                                 'User-Agent': 'OpenTrack India source-research/1.0'})
    try:
        with urllib.request.urlopen(req, timeout=7) as response:
            raw = response.read(150_000)
            doc = json.loads(raw)
            print(f'{number}: HTTP {response.status}, {len(raw)} bytes in {time.monotonic()-began:.1f}s', flush=True)
            if not isinstance(doc, dict):
                print(f'  response type: {type(doc).__name__}', flush=True)
                return
            print('  keys: ' + str(list(doc)[:35]), flush=True)
            for key in ('train_no', 'train_number', 'date', 'train_start_date', 'status',
                        'source', 'data_from', 'last_updated', 'error', 'message'):
                if key in doc and not isinstance(doc[key], (dict, list)):
                    print(f'  {key}: {str(doc[key])[:150]}', flush=True)
            for key, value in doc.items():
                if isinstance(value, list):
                    print(f'  {key}: {len(value)} rows', flush=True)
                    for row in value[:2]:
                        if isinstance(row, dict):
                            print('    field names: ' + str(list(row)[:35]), flush=True)
                            print('    station/time fields: ' + str({k: str(v)[:65] for k,v in row.items()
                                if any(word in k.lower() for word in ('station','time','date','actual',
                                    'estimate','arrival','departure','source','status'))})[:500], flush=True)
                elif isinstance(value, dict):
                    print(f'  {key}: object keys ' + str(list(value)[:30]), flush=True)
    except urllib.error.HTTPError as error:
        print(f'{number}: HTTP {error.code} in {time.monotonic()-began:.1f}s', flush=True)
    except (urllib.error.URLError, TimeoutError, OSError, ValueError) as error:
        print(f'{number}: {type(error).__name__}: {str(error)[:120]} in {time.monotonic()-began:.1f}s', flush=True)


if __name__ == '__main__':
    print('UNOFFICIAL API trial only: do not integrate or interpret unexplained fields as actuals.', flush=True)
    probe('20689', 'YPR', 'SMET')
    probe('20652', 'TLGP', 'SBC')
