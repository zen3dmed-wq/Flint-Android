"""Private CI-only VLESS -> HTTP fixture for Android emulator TUN tests.

Both listeners bind to 127.0.0.1. Android emulator reaches the VLESS listener
through its standard host alias 10.0.2.2. No production account/server is used.
"""
import argparse
import http.client
import http.server
import json
import pathlib
import signal
import socket
import struct
import subprocess
import threading
import time
import uuid
from urllib.parse import urlsplit

FIXTURE_ID = '11111111-1111-4111-8111-111111111111'
MARKER = b'FLINT_VPN_TUNNEL_OK'


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        with self.server.request_lock:
            self.server.request_count += 1
            count = self.server.request_count
            with self.server.request_log.open('a', encoding='utf-8') as log:
                log.write(json.dumps({'request': count, 'path': urlsplit(self.path).path[:160],
                                      'markerBytes': len(MARKER), 'epochMs': int(time.time() * 1000)}) + '\n')
        self.send_response(200)
        self.send_header('Content-Type', 'text/plain; charset=utf-8')
        self.send_header('Content-Length', str(len(MARKER)))
        self.send_header('Connection', 'close')
        self.send_header('X-Flint-Fixture-Request', str(count))
        self.end_headers()
        self.wfile.write(MARKER)

    def log_message(self, fmt, *args):
        pass


def self_test(directory):
    """Refuse readiness unless HTTP works through an actual VLESS handshake."""
    direct = http.client.HTTPConnection('127.0.0.1', 18080, timeout=5)
    try:
        direct.request('GET', '/self-test/direct')
        response = direct.getresponse()
        assert response.status == 200 and response.read() == MARKER, 'Direct fixture HTTP failed'
    finally:
        direct.close()
    with socket.create_connection(('127.0.0.1', 18443), timeout=5) as stream:
        stream.sendall(b'\x00' + uuid.UUID(FIXTURE_ID).bytes + b'\x00\x01'
                       + struct.pack('!H', 18080) + b'\x01' + socket.inet_aton('198.18.0.1')
                       + b'GET /self-test/vless HTTP/1.1\r\nHost: 198.18.0.1:18080\r\nConnection: close\r\n\r\n')
        def read_exact(count):
            data = b''
            while len(data) < count:
                part = stream.recv(count - len(data))
                if not part:
                    raise RuntimeError('VLESS fixture closed before response')
                data += part
            return data
        header = read_exact(2)
        assert header[0] == 0, 'Unexpected VLESS response version'
        if header[1]:
            read_exact(header[1])
        response = http.client.HTTPResponse(stream)
        response.begin()
        assert response.status == 200 and response.read() == MARKER, 'VLESS fixture round trip failed'
    (directory / 'fixture-self-test.json').write_text(json.dumps({
        'directHttp': 'passed', 'vlessRoundTrip': 'passed', 'syntheticUi': False,
        'scope': 'Host-side fixture verification before Android emulator test',
        'target': '198.18.0.1:18080', 'redirect': '127.0.0.1:18080'
    }, indent=2) + '\n', encoding='utf-8')


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--binary', required=True, type=pathlib.Path)
    parser.add_argument('--directory', required=True, type=pathlib.Path)
    args = parser.parse_args()
    args.directory.mkdir(parents=True, exist_ok=True)
    # A failed rerun must not leave a successful readiness marker from an old run.
    (args.directory / 'ready').unlink(missing_ok=True)
    config = args.directory / 'fixture-xray.json'
    config.write_text(json.dumps({
        'log': {'loglevel': 'info'},
        'inbounds': [{'listen': '127.0.0.1', 'port': 18443, 'protocol': 'vless',
                      'settings': {'decryption': 'none', 'clients': [{'id': FIXTURE_ID}]},
                      'streamSettings': {'network': 'tcp', 'security': 'none'}}],
        # Current Xray blocks private destinations by default for VLESS inbounds,
        # including a loopback redirect. Allow only the fixture HTTP endpoint.
        'outbounds': [{'protocol': 'freedom', 'settings': {'redirect': '127.0.0.1:18080',
                       'finalRules': [{'action': 'allow', 'network': 'tcp',
                                       'ip': ['127.0.0.1/32'], 'port': '18080'}]}}]
    }), encoding='utf-8')
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 18080), Handler)
    server.daemon_threads = True
    server.request_count = 0
    server.request_lock = threading.Lock()
    server.request_log = args.directory / 'fixture-http.jsonl'
    server.request_log.write_text('', encoding='utf-8')
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    stopped = threading.Event()
    signal.signal(signal.SIGTERM, lambda *_: stopped.set())
    signal.signal(signal.SIGINT, lambda *_: stopped.set())
    with (args.directory / 'fixture-xray.log').open('wb') as log:
        child = subprocess.Popen([str(args.binary.resolve()), 'run', '-config', str(config.resolve())], stdout=log, stderr=log)
        try:
            for _ in range(100):
                if child.poll() is not None:
                    raise RuntimeError('Fixture core failed; inspect fixture-xray.log')
                try:
                    with socket.create_connection(('127.0.0.1', 18443), timeout=0.2):
                        break
                except OSError:
                    time.sleep(0.1)
            else:
                raise RuntimeError('Fixture core readiness timed out')
            self_test(args.directory)
            (args.directory / 'ready').write_text('loopback-only\n', encoding='ascii')
            print('Flint local VPN fixture ready on loopback ports 18443 and 18080', flush=True)
            while not stopped.wait(0.5):
                if child.poll() is not None:
                    raise RuntimeError('Fixture core exited unexpectedly')
        finally:
            child.terminate()
            try:
                child.wait(timeout=5)
            except subprocess.TimeoutExpired:
                child.kill()
                child.wait()
            server.shutdown()
            server.server_close()


if __name__ == '__main__':
    main()
