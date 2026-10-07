"""Extract selected geosite/geoip groups, preserving Xray matching semantics.

Inputs: V2Fly domain-list-community dlc.dat and v2fly/geoip geoip.dat.
No code from the inputs is executed. Output is shared by all Flint clients.
"""
import hashlib
import ipaddress
import json
import sys
from pathlib import Path

def varint(data, pos):
    value = 0
    for shift in range(0, 70, 7):
        b = data[pos]; pos += 1
        value |= (b & 127) << shift
        if b < 128: return value, pos
    raise ValueError('invalid varint')

def fields(data):
    pos = 0
    while pos < len(data):
        tag, pos = varint(data, pos)
        field, wire = tag >> 3, tag & 7
        if wire == 0: value, pos = varint(data, pos)
        elif wire == 2:
            size, pos = varint(data, pos)
            value = data[pos:pos+size]; pos += size
            if len(value) != size: raise ValueError('truncated protobuf')
        else: raise ValueError(f'unsupported wire {wire}')
        yield field, value

def extract(site_file, ip_file):
    sites, ips = {}, {}
    groups = {'category-ru','category-gov-ru','category-bank-ru','tld-ru','yandex','vk','mailru-group','ozon','wildberries','rzd'}
    for f, data in fields(site_file):
        if f != 1: continue
        record = list(fields(data)); name = next(v.decode().lower() for k,v in record if k == 1)
        if name not in groups: continue
        rules = []
        for k,v in record:
            if k != 2: continue
            d = dict(fields(v)); kind = d.get(1,0); value = d[2].decode()
            rules.append({0:'keyword:',1:'regexp:',2:'domain:',3:'full:'}[kind]+value)
        sites[name] = sorted(set(rules))
    for f, data in fields(ip_file):
        if f != 1: continue
        record = list(fields(data)); name = next(v.decode().lower() for k,v in record if k == 1)
        if name not in {'ru','private'}: continue
        assert not any(k == 3 and v for k,v in record), 'reverse geoip unsupported'
        rules=[]
        for k,v in record:
            if k != 2: continue
            d=dict(fields(v)); addr=ipaddress.ip_address(d[1]); prefix=d.get(2,0)
            rules.append(str(ipaddress.ip_network(f'{addr}/{prefix}',strict=False)))
        ips[name]=sorted(set(rules))
    assert sites.get('category-ru') and ips.get('ru')
    return {'version':'2026-10-01','sources':{
        'geosite':{'url':'https://github.com/v2fly/domain-list-community','sha256':hashlib.sha256(site_file).hexdigest(),'license':'MIT'},
        'geoip':{'url':'https://github.com/v2fly/geoip','sha256':hashlib.sha256(ip_file).hexdigest(),'license':'CC-BY-SA-4.0'}},
        'geosite':sites,'geoip':ips}

if __name__ == '__main__':
    result=extract(Path(sys.argv[1]).read_bytes(),Path(sys.argv[2]).read_bytes())
    Path(sys.argv[3]).write_text(json.dumps(result,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    print(json.dumps({k:{name:len(rules) for name,rules in result[k].items()} for k in ('geosite','geoip')}))
