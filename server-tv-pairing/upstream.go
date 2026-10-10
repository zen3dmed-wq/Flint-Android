package pairing

import (
	"encoding/json"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// APIAuthorizer only GETs /me and /subscriptions. It neither refreshes tokens
// nor makes login, billing or device/session mutations in the existing backend.
type APIAuthorizer struct {
	Base   string
	Client *http.Client
}

func NewAPIAuthorizer(base string) (*APIAuthorizer, error) {
	u, err := url.Parse(base)
	if err != nil || u.Scheme != "https" || u.Hostname() == "" || u.User != nil || u.RawQuery != "" || u.Fragment != "" || !strings.HasSuffix(u.Path, "/api/v1") {
		return nil, Fault{500, "invalid_upstream_configuration"}
	}
	return &APIAuthorizer{Base: strings.TrimRight(base, "/"), Client: &http.Client{Timeout: 12 * time.Second, CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}}, nil
}
func (a *APIAuthorizer) get(r *http.Request, path string, out any) error {
	token := r.Header.Get("Authorization")
	if !strings.HasPrefix(token, "Bearer ") || len(token) > 8192 || len(token) < 16 {
		return Fault{401, "unauthorized"}
	}
	request, err := http.NewRequestWithContext(r.Context(), "GET", a.Base+path, nil)
	if err != nil {
		return err
	}
	request.Header.Set("Authorization", token)
	request.Header.Set("Accept", "application/json")
	request.Header.Set("User-Agent", "Flint-Pairing/1")
	response, err := a.Client.Do(request)
	if err != nil {
		return Fault{503, "upstream_unavailable"}
	}
	defer response.Body.Close()
	if response.StatusCode == 401 || response.StatusCode == 403 {
		return Fault{response.StatusCode, "unauthorized"}
	}
	if response.StatusCode == 429 {
		return Fault{429, "upstream_rate_limited"}
	}
	if response.StatusCode != 200 {
		return Fault{503, "upstream_unavailable"}
	}
	data, err := io.ReadAll(io.LimitReader(response.Body, 2_000_001))
	if err != nil || len(data) > 2_000_000 || json.Unmarshal(data, out) != nil {
		return Fault{503, "upstream_unavailable"}
	}
	return nil
}
func (a *APIAuthorizer) Account(r *http.Request) (Identity, error) {
	var value struct {
		ID json.RawMessage `json:"id"`
	}
	if err := a.get(r, "/me", &value); err != nil {
		return Identity{}, err
	}
	var id string
	if json.Unmarshal(value.ID, &id) != nil {
		return Identity{}, Fault{503, "upstream_unavailable"}
	}
	if id == "" || len(id) > 128 {
		return Identity{}, Fault{401, "unauthorized"}
	}
	return Identity{ID: id}, nil
}
func (a *APIAuthorizer) Subscription(r *http.Request, id string) error {
	var value struct {
		Items []struct {
			ID      string `json:"id"`
			Status  string `json:"status"`
			URL     string `json:"subscriptionUrl"`
			Expires string `json:"expiresAt"`
			Traffic *struct {
				LimitReached bool `json:"limitReached"`
			} `json:"traffic"`
		} `json:"items"`
	}
	if err := a.get(r, "/subscriptions", &value); err != nil {
		return err
	}
	for _, sub := range value.Items {
		if sub.ID != id {
			continue
		}
		if sub.Status != "active" || sub.URL == "" || (sub.Traffic != nil && sub.Traffic.LimitReached) {
			return Fault{403, "subscription_inactive"}
		}
		if sub.Expires != "" {
			expiry, err := time.Parse(time.RFC3339, sub.Expires)
			if err != nil || !time.Now().Before(expiry) {
				return Fault{403, "subscription_inactive"}
			}
		}
		return nil
	}
	return Fault{403, "subscription_not_owned"}
}
