package pairing

import (
	"bytes"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

type fakeAuth struct{}

func (fakeAuth) Account(r *http.Request) (Identity, error) {
	if r.Header.Get("Authorization") == "Bearer account-one" {
		return Identity{"one"}, nil
	}
	if r.Header.Get("Authorization") == "Bearer account-two" {
		return Identity{"two"}, nil
	}
	return Identity{}, Fault{401, "unauthorized"}
}
func (f fakeAuth) Subscription(r *http.Request, id string) error {
	who, err := f.Account(r)
	if err != nil {
		return err
	}
	if id != "subscription-"+who.ID {
		return Fault{403, "subscription_not_owned"}
	}
	return nil
}

type fixture struct {
	b            *Broker
	now          time.Time
	id, verifier string
	approve      map[string]any
}

func call(b http.Handler, path string, input any, token string) (int, map[string]any) {
	data, _ := json.Marshal(input)
	r := httptest.NewRequest("POST", Prefix+path, bytes.NewReader(data))
	r.RemoteAddr = "192.0.2.1:12345"
	r.Header.Set("Content-Type", "application/json")
	if token != "" {
		r.Header.Set("Authorization", "Bearer "+token)
	}
	w := httptest.NewRecorder()
	b.ServeHTTP(w, r)
	var out map[string]any
	_ = json.Unmarshal(w.Body.Bytes(), &out)
	return w.Code, out
}
func setup(t *testing.T) *fixture {
	t.Helper()
	f := &fixture{b: New(fakeAuth{}, false), now: time.Now()}
	f.b.now = func() time.Time { return f.now }
	f.verifier, _ = secret()
	sum := sha256.Sum256([]byte(f.verifier))
	status, out := call(f.b, "start", map[string]any{"codeChallenge": base64.RawURLEncoding.EncodeToString(sum[:]), "device": Device{ID: "test-tv", Platform: "android-tv", Model: "Test television"}}, "")
	if status != 201 {
		t.Fatalf("start: %d %v", status, out)
	}
	f.id = out["pairingId"].(string)
	f.approve = map[string]any{"pairingId": f.id, "subscriptionId": "subscription-one", "requestId": "00000000-0000-0000-0000-000000000001",
		"encryptedSettings": Envelope{Version: 1, Nonce: base64.RawURLEncoding.EncodeToString(make([]byte, 12)), Ciphertext: base64.RawURLEncoding.EncodeToString(make([]byte, 40))}}
	return f
}
func (f *fixture) proof() map[string]any {
	return map[string]any{"pairingId": f.id, "codeVerifier": f.verifier}
}
func TestCrossNetworkPairingNeedsNoTelegramAndRedeliversUntilAck(t *testing.T) {
	f := setup(t)
	if s, _ := call(f.b, "complete", f.proof(), ""); s != 202 {
		t.Fatal(s)
	}
	if s, out := call(f.b, "inspect", map[string]any{"pairingId": f.id}, "account-one"); s != 200 || out["device"] == nil {
		t.Fatal(s, out)
	}
	if s, _ := call(f.b, "approve", f.approve, "account-one"); s != 200 {
		t.Fatal(s)
	}
	for n := 0; n < 2; n++ {
		if s, out := call(f.b, "complete", f.proof(), ""); s != 200 || out["encryptedSettings"] == nil {
			t.Fatal(s, out)
		}
	}
	if s, _ := call(f.b, "ack", f.proof(), ""); s != 204 {
		t.Fatal(s)
	}
	if s, _ := call(f.b, "ack", f.proof(), ""); s != 204 {
		t.Fatal("ACK idempotency", s)
	}
	if s, _ := call(f.b, "complete", f.proof(), ""); s != 410 {
		t.Fatal(s)
	}
	if f.b.sessions[f.id].envelope != nil {
		t.Fatal("Consumed ciphertext retained")
	}
}
func TestQrIdentifierAloneCannotReceiveOrCancelSettings(t *testing.T) {
	f := setup(t)
	wrong, _ := secret()
	for _, action := range []string{"complete", "ack", "cancel"} {
		if s, _ := call(f.b, action, map[string]any{"pairingId": f.id, "codeVerifier": wrong}, ""); s != 401 {
			t.Fatal(action, s)
		}
	}
	if s, _ := call(f.b, "inspect", map[string]any{"pairingId": f.id}, ""); s != 401 {
		t.Fatal(s)
	}
	if s, _ := call(f.b, "approve", f.approve, ""); s != 401 {
		t.Fatal(s)
	}
}
func TestOnlyOwnedActiveSubscriptionAndExactIdempotentRetryCanApprove(t *testing.T) {
	f := setup(t)
	if s, _ := call(f.b, "approve", f.approve, "account-two"); s != 403 {
		t.Fatal(s)
	}
	if s, _ := call(f.b, "approve", f.approve, "account-one"); s != 200 {
		t.Fatal(s)
	}
	if s, _ := call(f.b, "approve", f.approve, "account-one"); s != 200 {
		t.Fatal("idempotent retry", s)
	}
	changed := map[string]any{}
	for k, v := range f.approve {
		changed[k] = v
	}
	changed["requestId"] = "00000000-0000-0000-0000-000000000002"
	if s, _ := call(f.b, "approve", changed, "account-one"); s != 409 {
		t.Fatal(s)
	}
	changed["subscriptionId"] = "subscription-two"
	if s, _ := call(f.b, "approve", changed, "account-two"); s != 409 {
		t.Fatal(s)
	}
}
func TestExpiryCancelAndPollingLimits(t *testing.T) {
	f := setup(t)
	if s, _ := call(f.b, "complete", f.proof(), ""); s != 202 {
		t.Fatal(s)
	}
	if s, out := call(f.b, "complete", f.proof(), ""); s != 429 || out["code"] != "slow_down" {
		t.Fatal(s, out)
	}
	f.now = f.now.Add(2 * time.Second)
	if s, _ := call(f.b, "complete", f.proof(), ""); s != 202 {
		t.Fatal(s)
	}
	f.now = f.now.Add(Lifetime)
	if s, _ := call(f.b, "complete", f.proof(), ""); s != 410 {
		t.Fatal(s)
	}
	f = setup(t)
	if s, _ := call(f.b, "cancel", f.proof(), ""); s != 204 {
		t.Fatal(s)
	}
	if s, _ := call(f.b, "approve", f.approve, "account-one"); s != 410 {
		t.Fatal(s)
	}
}
func TestConcurrentApprovalsHaveExactlyOneWinner(t *testing.T) {
	f := setup(t)
	var group sync.WaitGroup
	statuses := make(chan int, 10)
	for i := 0; i < 10; i++ {
		group.Add(1)
		go func(i int) {
			defer group.Done()
			input := map[string]any{}
			for k, v := range f.approve {
				input[k] = v
			}
			input["requestId"] = fmt.Sprintf("00000000-0000-0000-0000-%012d", i)
			s, _ := call(f.b, "approve", input, "account-one")
			statuses <- s
		}(i)
	}
	group.Wait()
	close(statuses)
	success := 0
	for s := range statuses {
		if s == 200 {
			success++
		} else if s != 409 {
			t.Fatal(s)
		}
	}
	if success != 1 {
		t.Fatal(success)
	}
}
func TestEnvelopeBoundsUnknownFieldsAndNoAuthorizationBypass(t *testing.T) {
	f := setup(t)
	bad := map[string]any{}
	for k, v := range f.approve {
		bad[k] = v
	}
	bad["extra"] = true
	if s, _ := call(f.b, "approve", bad, "account-one"); s != 400 {
		t.Fatal(s)
	}
	bad = map[string]any{}
	for k, v := range f.approve {
		bad[k] = v
	}
	bad["encryptedSettings"] = Envelope{Version: 1, Nonce: "bad", Ciphertext: "bad"}
	if s, _ := call(f.b, "approve", bad, "account-one"); s != 400 {
		t.Fatal(s)
	}
	if s, _ := call(New(nil, false), "start", map[string]any{}, ""); s != 503 {
		t.Fatal(s)
	}
}
func TestUpstreamOnlyReadsAndRejectsInactiveUnownedAndRedirects(t *testing.T) {
	var mu sync.Mutex
	var paths []string
	status := "active"
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		paths = append(paths, r.Method+" "+r.URL.Path)
		if r.Header.Get("Authorization") != "Bearer account-one" {
			w.WriteHeader(401)
			return
		}
		if r.URL.Path == "/api/v1/me" {
			write(w, 200, map[string]string{"id": "one"})
			return
		}
		write(w, 200, map[string]any{"items": []map[string]any{{"id": "one", "status": status, "subscriptionUrl": "https://subscription.example.invalid/key", "expiresAt": time.Now().Add(time.Hour).UTC().Format(time.RFC3339)}}})
	}))
	defer upstream.Close()
	a := &APIAuthorizer{Base: upstream.URL + "/api/v1", Client: upstream.Client()}
	r := httptest.NewRequest("POST", "/", nil)
	r.Header.Set("Authorization", "Bearer account-one")
	if identity, err := a.Account(r); err != nil || identity.ID != "one" {
		t.Fatal(identity, err)
	}
	if err := a.Subscription(r, "one"); err != nil {
		t.Fatal(err)
	}
	if err := a.Subscription(r, "another"); err == nil {
		t.Fatal("unowned accepted")
	}
	mu.Lock()
	status = "expired"
	mu.Unlock()
	if err := a.Subscription(r, "one"); err == nil {
		t.Fatal("inactive accepted")
	}
	for _, p := range paths {
		if p != "GET /api/v1/me" && p != "GET /api/v1/subscriptions" {
			t.Fatal(p)
		}
	}
	for _, base := range []string{"http://flintmain.ru/api/v1", "https://user:pass@flintmain.ru/api/v1", "https://flintmain.ru/api/v1?secret=1"} {
		if _, err := NewAPIAuthorizer(base); err == nil {
			t.Fatal(base)
		}
	}
}
