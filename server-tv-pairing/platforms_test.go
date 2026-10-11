package pairing

import (
	"crypto/sha256"
	"encoding/base64"
	"testing"
)

func TestEveryClientPlatformCanReceiveAcrossNetworks(t *testing.T) {
	for _, platform := range []string{"android", "android-tv", "windows", "ios"} {
		t.Run(platform, func(t *testing.T) {
			b := New(fakeAuth{}, false)
			verifier, _ := secret()
			sum := sha256.Sum256([]byte(verifier))
			status, result := call(b, "start", map[string]any{
				"codeChallenge": base64.RawURLEncoding.EncodeToString(sum[:]),
				"device":        Device{ID: "stable-device", Platform: platform, Model: "Test " + platform},
			}, "")
			if status != 201 {
				t.Fatalf("start status %d", status)
			}
			id := result["pairingId"].(string)
			status, result = call(b, "inspect", map[string]any{"pairingId": id}, "account-one")
			if status != 200 || result["device"].(map[string]any)["platform"] != platform {
				t.Fatalf("inspect did not preserve receiving platform: %d", status)
			}
			status, _ = call(b, "approve", map[string]any{
				"pairingId": id, "subscriptionId": "subscription-one",
				"requestId": "00000000-0000-0000-0000-000000000001",
				"encryptedSettings": Envelope{Version: 1,
					Nonce:      base64.RawURLEncoding.EncodeToString(make([]byte, 12)),
					Ciphertext: base64.RawURLEncoding.EncodeToString(make([]byte, 40))},
			}, "account-one")
			if status != 200 {
				t.Fatalf("approve status %d", status)
			}
			proof := map[string]any{"pairingId": id, "codeVerifier": verifier}
			if status, _ = call(b, "complete", proof, ""); status != 200 {
				t.Fatalf("complete status %d", status)
			}
			if status, _ = call(b, "ack", proof, ""); status != 204 {
				t.Fatalf("ack status %d", status)
			}
		})
	}
}

func TestUnknownReceivingPlatformIsRejected(t *testing.T) {
	verifier, _ := secret()
	sum := sha256.Sum256([]byte(verifier))
	status, _ := call(New(fakeAuth{}, false), "start", map[string]any{
		"codeChallenge": base64.RawURLEncoding.EncodeToString(sum[:]),
		"device":        Device{ID: "test", Platform: "unrecognized", Model: "Test"},
	}, "")
	if status != 400 {
		t.Fatalf("unexpected platform accepted: %d", status)
	}
}
