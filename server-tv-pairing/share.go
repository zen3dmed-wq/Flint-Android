package pairing

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"net/http"
	"time"
)

// An owner shows this QR. The recipient needs neither an account nor Telegram.
// The encryption key is a URL fragment and is never sent to this service.
const ShareLifetime = 15 * time.Minute

func (b *Broker) shareStart(w http.ResponseWriter, r *http.Request) error {
	if !b.rate(r, "share-start", 20) {
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
		Subscription string `json:"subscriptionId"`
		Challenge    string `json:"claimChallenge"`
		Request      string `json:"requestId"`
	}
	if err = body(w, r, &input); err != nil {
		return err
	}
	if !validID(input.Challenge) || input.Subscription == "" || len(input.Subscription) > 128 || !requestIdentifier.MatchString(input.Request) {
		return Fault{400, "invalid_request"}
	}
	if err = b.auth.Subscription(r, input.Subscription); err != nil {
		return err
	}
	id, err := secret()
	if err != nil {
		return err
	}
	return b.lockedReply(w, func() (reply, error) {
		b.gc()
		for existing, e := range b.sessions {
			if e.senderInitiated && e.approver == account.ID && e.requestID == input.Request {
				if e.subscription != input.Subscription || e.shareChallenge != input.Challenge || e.acknowledged {
					return reply{}, Fault{409, "share_request_conflict"}
				}
				return reply{201, map[string]any{"pairingId": existing, "expiresAt": e.expires.UTC().Format(time.RFC3339), "expiresInSeconds": int(e.expires.Sub(b.now()).Seconds())}}, nil
			}
		}
		if len(b.sessions) >= 2000 {
			return reply{}, Fault{503, "device_pairing_unavailable"}
		}
		expiry := b.now().Add(ShareLifetime)
		b.sessions[id] = &entry{senderInitiated: true, shareChallenge: input.Challenge, approver: account.ID, subscription: input.Subscription, requestID: input.Request, expires: expiry}
		return reply{201, map[string]any{"pairingId": id, "expiresAt": expiry.UTC().Format(time.RFC3339), "expiresInSeconds": 900}}, nil
	})
}

func validDevice(d Device) bool {
	return supportedPlatform(d.Platform) && d.ID != "" && len(d.ID) <= 128 && d.Model != "" && len(d.Model) <= 120 && len(d.OS) <= 40 && len(d.Version) <= 32
}
func (b *Broker) shareClaim(w http.ResponseWriter, r *http.Request) error {
	if !b.rate(r, "share-claim", 30) {
		return Fault{429, "rate_limited"}
	}
	var input struct {
		ID        string `json:"pairingId"`
		Secret    string `json:"claimSecret"`
		Challenge string `json:"codeChallenge"`
		Device    Device `json:"device"`
	}
	if err := body(w, r, &input); err != nil {
		return err
	}
	if !validID(input.Secret) || !validID(input.Challenge) || !validDevice(input.Device) {
		return Fault{400, "invalid_request"}
	}
	sum := sha256.Sum256([]byte(input.Secret))
	challenge := base64.RawURLEncoding.EncodeToString(sum[:])
	return b.lockedReply(w, func() (reply, error) {
		e, err := b.session(input.ID)
		if err != nil {
			return reply{}, err
		}
		if !e.senderInitiated || subtle.ConstantTimeCompare([]byte(e.shareChallenge), []byte(challenge)) != 1 {
			return reply{}, Fault{401, "invalid_share_proof"}
		}
		if e.envelope == nil {
			return reply{}, Fault{409, "share_not_ready"}
		}
		if e.challenge != "" && e.challenge != input.Challenge {
			return reply{}, Fault{409, "share_already_claimed"}
		}
		e.challenge = input.Challenge
		e.device = input.Device
		copyEnvelope := *e.envelope
		return reply{200, map[string]any{"encryptedSettings": copyEnvelope}}, nil
	})
}

func (b *Broker) shareCancel(w http.ResponseWriter, r *http.Request) error {
	if !b.rate(r, "share-cancel", 30) {
		return Fault{429, "rate_limited"}
	}
	account, err := b.auth.Account(r)
	if err != nil {
		return err
	}
	var input struct {
		ID string `json:"pairingId"`
	}
	if err = body(w, r, &input); err != nil {
		return err
	}
	return b.lockedReply(w, func() (reply, error) {
		e, err := b.session(input.ID)
		if err != nil {
			return reply{}, err
		}
		if !e.senderInitiated || e.approver != account.ID {
			return reply{}, Fault{403, "share_not_owned"}
		}
		b.drop(e)
		e.acknowledged = true
		return reply{204, nil}, nil
	})
}
