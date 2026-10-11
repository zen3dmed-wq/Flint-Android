"""Private CI-only VLESS -> HTTP fixture for Android emulator TUN tests.

Both listeners bind to 127.0.0.1. Android emulator reaches the VLESS listener
through its standard host alias 10.0.2.2. No production account/server is used.
"""
import argparse
import base64
import http.client
import http.server
import json
import pathlib
import signal
import select
import socket
import socketserver
import struct
import ssl
import subprocess
import threading
import time
import uuid
from urllib.parse import urlsplit

FIXTURE_ID = '11111111-1111-4111-8111-111111111111'
MARKER = b'FLINT_VPN_TUNNEL_OK'
FAULT = threading.Event()


class FaultRelay(socketserver.BaseRequestHandler):
    """A disposable node whose established connections can be killed by the test."""
    def handle(self):
        if FAULT.is_set(): return
        try:
            with socket.create_connection(('127.0.0.1', 18443), timeout=2) as upstream:
                peers = [self.request, upstream]
                while not FAULT.is_set():
                    readable, _, _ = select.select(peers, [], [], .2)
                    for source in readable:
                        data = source.recv(65536)
                        if not data: return
                        (upstream if source is self.request else self.request).sendall(data)
        except OSError:
            pass


class DnsHandler(socketserver.BaseRequestHandler):
    def handle(self):
        packet, sock = self.request
        # The fixture returns an address outside the RU/private routing catalog.
        # It is never a public resolver and only binds to loopback.
        end = 12
        while end < len(packet) and packet[end]:
            end += packet[end] + 1
        end += 5
        if end > len(packet):
            return
        qtype = struct.unpack('!H', packet[end-4:end-2])[0]
        answer = b'\xc0\x0c\x00\x01\x00\x01\x00\x00\x00\x00\x00\x04' + socket.inet_aton('93.184.215.14') if qtype == 1 else b''
        response = packet[:2] + b'\x81\x80\x00\x01' + struct.pack('!H', bool(answer)) + b'\x00\x00\x00\x00' + packet[12:end] + answer
        sock.sendto(response, self.client_address)


class Handler(http.server.BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/fixture/fault-on': FAULT.set()
        if self.path == '/fixture/fault-off': FAULT.clear()
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
    # A local TLS 1.3 target allows a real Reality handshake without external sites.
    certificate = args.directory / 'tls.crt'
    private = args.directory / 'tls.key'
    subprocess.run(['openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(private), '-out', str(certificate), '-days', '1', '-subj', '/CN=localhost', '-addext', 'subjectAltName=DNS:localhost'], check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    private_der = subprocess.check_output(['openssl', 'genpkey', '-algorithm', 'X25519', '-outform', 'DER'])
    public_der = subprocess.check_output(['openssl', 'pkey', '-inform', 'DER', '-pubout', '-outform', 'DER'], input=private_der)
    # X25519 PKCS#8/SPKI DER end with the raw 32-byte private/public key.
    reality_private = base64.urlsafe_b64encode(private_der[-32:]).rstrip(b'=').decode('ascii')
    reality_public = base64.urlsafe_b64encode(public_der[-32:]).rstrip(b'=').decode('ascii')
    (args.directory / 'reality-public-key').write_text(reality_public, encoding='ascii')
    config.write_text(json.dumps({
        'log': {'loglevel': 'info'},
        'inbounds': [{'listen': '127.0.0.1', 'port': 18443, 'protocol': 'vless',
                      'settings': {'decryption': 'none', 'clients': [{'id': FIXTURE_ID}]},
                      'streamSettings': {'network': 'tcp', 'security': 'none'}},
                     {'listen': '127.0.0.1', 'port': 18444, 'protocol': 'vless',
                      'settings': {'decryption': 'none', 'clients': [{'id': FIXTURE_ID}]},
                      'streamSettings': {'network': 'tcp', 'security': 'reality', 'realitySettings': {
                          'dest': '127.0.0.1:19443', 'serverNames': ['localhost'], 'privateKey': reality_private, 'shortIds': ['1234abcd']}}}],
        # Current Xray blocks private destinations by default for VLESS inbounds,
        # including a loopback redirect. Allow only the fixture HTTP endpoint.
        'outbounds': [{'tag': 'http', 'protocol': 'freedom', 'settings': {'redirect': '127.0.0.1:18080',
                       'finalRules': [{'action': 'allow', 'network': 'tcp',
                                       'ip': ['127.0.0.1/32'], 'port': '18080'}]}},
                      {'tag': 'dns', 'protocol': 'freedom', 'settings': {'redirect': '127.0.0.1:15353',
                       'finalRules': [{'action': 'allow', 'network': 'udp', 'ip': ['127.0.0.1/32'], 'port': '15353'}]}}],
        'routing': {'rules': [{'type': 'field', 'network': 'udp', 'port': '53', 'outboundTag': 'dns'}]}
    }), encoding='utf-8')
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 18080), Handler)
    server.daemon_threads = True
    server.request_count = 0
    server.request_lock = threading.Lock()
    server.request_log = args.directory / 'fixture-http.jsonl'
    server.request_log.write_text('', encoding='utf-8')
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    relay = socketserver.ThreadingTCPServer(('127.0.0.1', 18445), FaultRelay)
    relay.daemon_threads = True
    threading.Thread(target=relay.serve_forever, daemon=True).start()
    dns = socketserver.ThreadingUDPServer(('127.0.0.1', 15353), DnsHandler)
    threading.Thread(target=dns.serve_forever, daemon=True).start()
    tls_server = http.server.ThreadingHTTPServer(('127.0.0.1', 19443), Handler)
    tls_context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
    tls_context.minimum_version = ssl.TLSVersion.TLSv1_3
    tls_context.set_alpn_protocols(['h2', 'http/1.1'])
    tls_context.load_cert_chain(str(certificate), str(private))
    tls_server.socket = tls_context.wrap_socket(tls_server.socket, server_side=True)
    tls_server.request_lock = server.request_lock; tls_server.request_count = 0; tls_server.request_log = server.request_log
    threading.Thread(target=tls_server.serve_forever, daemon=True).start()
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
            dns.shutdown()
            dns.server_close()
            tls_server.shutdown(); tls_server.server_close()


if __name__ == '__main__':
    main()
