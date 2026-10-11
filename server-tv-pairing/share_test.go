package pairing

import (
	"crypto/sha256"
	"encoding/base64"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func ownerShare(t *testing.T) (*fixture, string) {
	t.Helper()
	f := setup(t)
	claim, _ := secret()
	sum := sha256.Sum256([]byte(claim))
	s, result := call(f.b, "share/start", map[string]any{"subscriptionId": "subscription-one", "claimChallenge": base64.RawURLEncoding.EncodeToString(sum[:]), "requestId": "00000000-0000-0000-0000-000000000002"}, "account-one")
	if s != 201 {
		t.Fatalf("share/start %d", s)
	}
	f.id = result["pairingId"].(string)
	f.approve["pairingId"] = f.id
	f.approve["requestId"] = "00000000-0000-0000-0000-000000000002"
	if s, _ = call(f.b, "approve", f.approve, "account-one"); s != 200 {
		t.Fatalf("approve %d", s)
	}
	return f, claim
}
func TestOwnerQrCanBeClaimedWithoutAccountOnceAndRedeliveredUntilAck(t *testing.T) {
	for _, platform := range []string{"android", "android-tv", "windows", "ios"} {
		t.Run(platform, func(t *testing.T) {
			f, claim := ownerShare(t)
			sum := sha256.Sum256([]byte(f.verifier))
			body := map[string]any{"pairingId": f.id, "claimSecret": claim, "codeChallenge": base64.RawURLEncoding.EncodeToString(sum[:]), "device": Device{ID: "receiver", Platform: platform, Model: "Receiver"}}
			for attempt := 0; attempt < 2; attempt++ {
				if s, _ := call(f.b, "share/claim", body, ""); s != 200 {
					t.Fatalf("claim/redelivery %d", s)
				}
			}
			other, _ := secret()
			otherSum := sha256.Sum256([]byte(other))
			body["codeChallenge"] = base64.RawURLEncoding.EncodeToString(otherSum[:])
			if s, _ := call(f.b, "share/claim", body, ""); s != 409 {
				t.Fatalf("second device claimed QR: %d", s)
			}
			if s, _ := call(f.b, "ack", f.proof(), ""); s != 204 {
				t.Fatalf("ack %d", s)
			}
			if s, _ := call(f.b, "share/claim", body, ""); s != 410 {
				t.Fatalf("consumed QR: %d", s)
			}
		})
	}
}
func TestOwnerShareRejectsMissingProofUnownedPublishingAndSupportsCancel(t *testing.T) {
	f, claim := ownerShare(t)
	wrong, _ := secret()
	sum := sha256.Sum256([]byte(f.verifier))
	input := map[string]any{"pairingId": f.id, "claimSecret": wrong, "codeChallenge": base64.RawURLEncoding.EncodeToString(sum[:]), "device": Device{ID: "receiver", Platform: "android", Model: "Receiver"}}
	if s, _ := call(f.b, "share/claim", input, ""); s != 401 {
		t.Fatalf("wrong proof: %d", s)
	}
	if s, _ := call(f.b, "approve", f.approve, "account-two"); s != 403 {
		t.Fatalf("foreign owner: %d", s)
	}
	if s, _ := call(f.b, "share/cancel", map[string]any{"pairingId": f.id}, "account-two"); s != 403 {
		t.Fatalf("foreign cancel: %d", s)
	}
	if s, _ := call(f.b, "share/cancel", map[string]any{"pairingId": f.id}, "account-one"); s != 204 {
		t.Fatalf("owner cancel: %d", s)
	}
	input["claimSecret"] = claim
	if s, _ := call(f.b, "share/claim", input, ""); s != 410 {
		t.Fatalf("cancelled share: %d", s)
	}
}
func TestInstallPageDoesNotNeedLoginAndDoesNotExposeEncryptedSettings(t *testing.T) {
	f, _ := ownerShare(t)
	for _, path := range []string{"/connect/" + f.id, Prefix + "web/connect.js", Prefix + "distribution", "/.well-known/assetlinks.json"} {
		r := httptest.NewRequest("GET", path, nil)
		w := httptest.NewRecorder()
		f.b.ServeHTTP(w, r)
		if w.Code != http.StatusOK {
			t.Fatalf("public install resource: %d", w.Code)
		}
		if strings.Contains(w.Body.String(), "encryptedSettings") || strings.Contains(w.Body.String(), "subscription-one") {
			t.Fatal("page leaks transfer")
		}
	}
}
