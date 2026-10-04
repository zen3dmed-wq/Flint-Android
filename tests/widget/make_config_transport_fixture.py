"""Synthetic config with the actual bundled RU rules; no credentials/network."""
import json
import sys
from pathlib import Path

repo = Path(__file__).resolve().parents[2]
data = json.loads((repo / 'flint/flint-routing-catalog.json').read_text(encoding='utf-8'))
domains = ['domain:zakupki.gov.ru'] + data['geosite']['category-ru'] + data['geosite']['tld-ru']
ips = data['geoip']['ru'] + data['geoip']['private']
xray = {'inbounds': [], 'outbounds': [{'protocol': 'freedom', 'tag': 'flint-direct'}],
        'routing': {'domainStrategy': 'IPIfNonMatch', 'rules': [
            {'type': 'field', 'domain': list(dict.fromkeys(domains)), 'outboundTag': 'flint-direct'},
            {'type': 'field', 'ip': list(dict.fromkeys(ips)), 'outboundTag': 'flint-direct'}]}}
outer = {'protocol': 'xray', 'description': 'TEST Армения 🐶',
         'xray_config_data': {'config': json.dumps(xray, ensure_ascii=False, separators=(',', ':'))}}
Path(sys.argv[1]).write_text(json.dumps(outer, ensure_ascii=False, indent=4), encoding='utf-8')
print('Synthetic routing fixture: %d IP rules, %d domain rules' % (len(ips), len(domains)))
