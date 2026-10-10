// Package pairing provides the isolated TV rendezvous handlers. It does not
// issue account tokens or make any changes to the existing Flint API/database.
package pairing

import (
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net"
	"net/http"
	"regexp"
	"strings"
	"sync"
	"time"
)

const Prefix = "/api/v1/devices/pairing/"
const Lifetime = 5 * time.Minute
const MaxCiphertext = 512*1024 + 16

var identifier = regexp.MustCompile(`^[A-Za-z0-9_-]{43}$`)
var requestIdentifier = regexp.MustCompile(`^[A-Za-z0-9_-]{16,80}$|^[a-fA-F0-9-]{36}$`)

type Device struct {
	ID       string `json:"deviceId"`
	Platform string `json:"platform"`
	Model    string `json:"model"`
	OS       string `json:"osVersion"`
	Version  string `json:"appVersion"`
}
type Envelope struct {
	Version    int    `json:"version"`
	Nonce      string `json:"nonce"`
	Ciphertext string `json:"ciphertext"`
}
type Identity struct{ ID string }

// Implement these two read-only hooks against the authoritative account API.
// Never trust an account/subscription ID supplied by the phone without them.
type Authorizer interface {
	Account(*http.Request) (Identity, error)
	Subscription(*http.Request, string) error
}
type Fault struct {
	Status int
	Code   string
}

func (f Fault) Error() string { return f.Code }

type entry struct {
	challenge                         string
	device                            Device
	expires                           time.Time
	envelope                          *Envelope
	approver, requestID, subscription string
	digest                            [32]byte
	acknowledged                      bool
	nextPoll                          time.Time
}
type rateBucket struct {
	start time.Time
	count int
}
type Broker struct {
	mu                 sync.Mutex
	sessions           map[string]*entry
	rates              map[string]rateBucket
	auth               Authorizer
	now                func() time.Time
	trustLoopbackProxy bool
	storedBytes        int
}

func New(auth Authorizer, trustLoopbackProxy bool) *Broker {
	return &Broker{sessions: map[string]*entry{}, rates: map[string]rateBucket{}, auth: auth, now: time.Now, trustLoopbackProxy: trustLoopbackProxy}
}
func secret() (string, error) {
	var bytes [32]byte
	if _, err := rand.Read(bytes[:]); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(bytes[:]), nil
}
func validID(id string) bool {
	data, err := base64.RawURLEncoding.DecodeString(id)
	return identifier.MatchString(id) && err == nil && len(data) == 32 && base64.RawURLEncoding.EncodeToString(data) == id
}
func (b *Broker) gc() {
	now := b.now()
	for id, e := range b.sessions {
		if !now.Before(e.expires) {
			b.drop(e)
			delete(b.sessions, id)
		}
	}
	for key, r := range b.rates {
		if now.Sub(r.start) > time.Minute {
			delete(b.rates, key)
		}
	}
}

// Drop only encrypted settings, never an account/session credential.
func (b *Broker) drop(e *entry) {
	if e.envelope != nil {
		b.storedBytes -= len(e.envelope.Ciphertext) + len(e.envelope.Nonce)
		e.envelope = nil
	}
}

type reply struct {
	status int
	value  any
}

func (b *Broker) lockedReply(w http.ResponseWriter, action func() (reply, error)) error {
	b.mu.Lock()
	value, err := func() (reply, error) { defer b.mu.Unlock(); return action() }()
	// Slow network clients must not hold the session mutex during a write.
	if err == nil {
		write(w, value.status, value.value)
	}
	return err
}
func (b *Broker) rate(r *http.Request, action string, limit int) bool {
	ip, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		ip = r.RemoteAddr
	}
	parsed := net.ParseIP(ip)
	if b.trustLoopbackProxy && parsed != nil && parsed.IsLoopback() {
		// The ingress MUST overwrite, not append, X-Real-IP. Only loopback
		// ingress connections may supply this header; arbitrary clients cannot.
		if original := net.ParseIP(r.Header.Get("X-Real-IP")); original != nil {
			ip = original.String()
		}
	}
	key := action + ":" + ip
	b.mu.Lock()
	defer b.mu.Unlock()
	b.gc()
	bucket := b.rates[key]
	if bucket.start.IsZero() {
		bucket.start = b.now()
	}
	if bucket.count >= limit || len(b.rates) > 10000 {
		return false
	}
	bucket.count++
	b.rates[key] = bucket
	return true
}
func write(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Pragma", "no-cache")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	if status != 204 {
		_ = json.NewEncoder(w).Encode(value)
	}
}
func failure(w http.ResponseWriter, err error) {
	var f Fault
	if !errors.As(err, &f) {
		f = Fault{503, "device_pairing_unavailable"}
	}
	if f.Status == 429 {
		w.Header().Set("Retry-After", "2")
	}
	write(w, f.Status, map[string]string{"code": f.Code})
}
func body(w http.ResponseWriter, r *http.Request, value any) error {
	media := strings.ToLower(strings.TrimSpace(strings.Split(r.Header.Get("Content-Type"), ";")[0]))
	if media != "application/json" {
		return Fault{415, "json_required"}
	}
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 750*1024))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(value); err != nil {
		return Fault{400, "invalid_request"}
	}
	if err := decoder.Decode(&struct{}{}); err != io.EOF {
		return Fault{400, "invalid_request"}
	}
	return nil
}
func (b *Broker) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.URL.Path == Prefix+"capabilities" && r.Method == "GET" {
		write(w, 200, map[string]any{"enabled": b.auth != nil, "version": 1})
		return
	}
	if r.Method != "POST" {
		failure(w, Fault{405, "method_not_allowed"})
		return
	}
	if b.auth == nil {
		failure(w, Fault{503, "device_pairing_unavailable"})
		return
	}
	var err error
	switch r.URL.Path {
	case Prefix + "start":
		err = b.start(w, r)
	case Prefix + "inspect":
		err = b.inspect(w, r)
	case Prefix + "approve":
		err = b.approve(w, r)
	case Prefix + "complete", Prefix + "ack", Prefix + "cancel":
		err = b.deviceRequest(w, r)
	default:
		err = Fault{404, "not_found"}
	}
	if err != nil {
		failure(w, err)
	}
}
func (b *Broker) start(w http.ResponseWriter, r *http.Request) error {
	if !b.rate(r, "start", 30) {
		return Fault{429, "rate_limited"}
	}
	var input struct {
		Challenge string `json:"codeChallenge"`
		Device    Device `json:"device"`
	}
	if err := body(w, r, &input); err != nil {
		return err
	}
	d := input.Device
	if !validID(input.Challenge) || d.Platform != "android-tv" || d.ID == "" || len(d.ID) > 128 || d.Model == "" || len(d.Model) > 120 || len(d.OS) > 40 || len(d.Version) > 32 {
		return Fault{400, "invalid_request"}
	}
	id, err := secret()
	if err != nil {
		return err
	}
	return b.lockedReply(w, func() (reply, error) {
		b.gc()
		if len(b.sessions) >= 5000 {
			return reply{}, Fault{503, "device_pairing_unavailable"}
		}
		expires := b.now().Add(Lifetime)
		b.sessions[id] = &entry{challenge: input.Challenge, device: d, expires: expires}
		return reply{201, map[string]any{"pairingId": id, "expiresAt": expires.UTC().Format(time.RFC3339), "expiresInSeconds": 300, "intervalSeconds": 2}}, nil
	})
}
func (b *Broker) session(id string) (*entry, error) {
	if !validID(id) {
		return nil, Fault{400, "invalid_pairing_id"}
	}
	e := b.sessions[id]
	if e == nil || !b.now().Before(e.expires) || e.acknowledged {
		return nil, Fault{410, "device_pairing_expired"}
	}
	return e, nil
}
func (b *Broker) inspect(w http.ResponseWriter, r *http.Request) error {
	if !b.rate(r, "inspect", 60) {
		return Fault{429, "rate_limited"}
	}
	if _, err := b.auth.Account(r); err != nil {
		return err
	}
	var input struct {
		ID string `json:"pairingId"`
	}
	if err := body(w, r, &input); err != nil {
		return err
	}
	return b.lockedReply(w, func() (reply, error) {
		e, err := b.session(input.ID)
		if err != nil {
			return reply{}, err
		}
		if e.envelope != nil {
			return reply{}, Fault{409, "device_pairing_already_approved"}
		}
		return reply{200, map[string]any{"device": e.device, "expiresAt": e.expires.UTC().Format(time.RFC3339)}}, nil
	})
}
func (b *Broker) approve(w http.ResponseWriter, r *http.Request) error {
	if !b.rate(r, "approve", 30) {
		return Fault{429, "rate_limited"}
	}
	account, err := b.auth.Account(r)
	if err != nil {
		return err
	}
	if account.ID == "" {
		return Fault{401, "unauthorized"}
	}
	var input struct {
		ID           string   `json:"pairingId"`
		Subscription string   `json:"subscriptionId"`
		Request      string   `json:"requestId"`
		Envelope     Envelope `json:"encryptedSettings"`
	}
	if err := body(w, r, &input); err != nil {
		return err
	}
	if input.Subscription == "" || len(input.Subscription) > 128 || !requestIdentifier.MatchString(input.Request) || input.Envelope.Version != 1 || len(input.Envelope.Ciphertext) > (MaxCiphertext*4/3+4) {
		return Fault{400, "invalid_request"}
	}
	nonce, err := base64.RawURLEncoding.DecodeString(input.Envelope.Nonce)
	if err != nil || len(nonce) != 12 {
		return Fault{400, "invalid_envelope"}
	}
	ciphertext, err := base64.RawURLEncoding.DecodeString(input.Envelope.Ciphertext)
	if err != nil || len(ciphertext) < 17 || len(ciphertext) > MaxCiphertext {
		return Fault{400, "invalid_envelope"}
	}
	if err := b.auth.Subscription(r, input.Subscription); err != nil {
		return err
	}
	encoded, _ := json.Marshal(input.Envelope)
	digest := sha256.Sum256(encoded)
	return b.lockedReply(w, func() (reply, error) {
		e, err := b.session(input.ID)
		if err != nil {
			return reply{}, err
		}
		if e.envelope != nil {
			if e.approver != account.ID || e.subscription != input.Subscription || e.requestID != input.Request || e.digest != digest {
				return reply{}, Fault{409, "device_pairing_already_approved"}
			}
		} else {
			size := len(input.Envelope.Ciphertext) + len(input.Envelope.Nonce)
			if b.storedBytes+size > 64*1024*1024 {
				return reply{}, Fault{503, "device_pairing_unavailable"}
			}
			b.storedBytes += size
			e.envelope = &input.Envelope
			e.approver = account.ID
			e.subscription = input.Subscription
			e.requestID = input.Request
			e.digest = digest
		}
		return reply{200, map[string]string{"status": "approved"}}, nil
	})
}
func (b *Broker) deviceRequest(w http.ResponseWriter, r *http.Request) error {
	// 180 polls per IP/minute allows multiple TVs behind one household router.
	if !b.rate(r, "poll", 180) {
		return Fault{429, "rate_limited"}
	}
	var input struct {
		ID       string `json:"pairingId"`
		Verifier string `json:"codeVerifier"`
	}
	if err := body(w, r, &input); err != nil {
		return err
	}
	if !validID(input.Verifier) {
		return Fault{401, "invalid_pairing_proof"}
	}
	sum := sha256.Sum256([]byte(input.Verifier))
	challenge := base64.RawURLEncoding.EncodeToString(sum[:])
	return b.lockedReply(w, func() (reply, error) {
		if !validID(input.ID) {
			return reply{}, Fault{400, "invalid_pairing_id"}
		}
		e := b.sessions[input.ID]
		if e == nil || !b.now().Before(e.expires) {
			return reply{}, Fault{410, "device_pairing_expired"}
		}
		if subtle.ConstantTimeCompare([]byte(e.challenge), []byte(challenge)) != 1 {
			return reply{}, Fault{401, "invalid_pairing_proof"}
		}
		action := strings.TrimPrefix(r.URL.Path, Prefix)
		if action == "cancel" {
			b.drop(e)
			e.acknowledged = true
			return reply{204, nil}, nil
		}
		if action == "ack" {
			if e.envelope == nil && !e.acknowledged {
				return reply{}, Fault{409, "device_pairing_not_ready"}
			}
			b.drop(e)
			e.acknowledged = true
			return reply{204, nil}, nil
		}
		if e.acknowledged {
			return reply{}, Fault{410, "device_pairing_expired"}
		}
		if e.envelope != nil {
			// Redelivery is deliberate until ACK: losing an HTTP response must not
			// consume the settings before the TV commits its private profile file.
			copyEnvelope := *e.envelope
			return reply{200, map[string]any{"encryptedSettings": copyEnvelope}}, nil
		}
		if b.now().Before(e.nextPoll) {
			return reply{}, Fault{429, "slow_down"}
		}
		e.nextPoll = b.now().Add(2 * time.Second)
		return reply{202, map[string]any{"status": "pending", "intervalSeconds": 2}}, nil
	})
}
