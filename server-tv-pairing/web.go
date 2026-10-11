package pairing

import (
	"embed"
	"encoding/json"
	"errors"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"time"
)

//go:embed web/*
var connectFiles embed.FS

// Optional, explicitly configured distribution. No existing website is edited.
type Distribution struct {
	AndroidAPK               string
	IOSURL                   string
	AndroidCertificateSHA256 string
	AppleApplicationID       string // Apple Team ID + ".app.flint.vpn"; empty until signed.
}

func (b *Broker) ConfigureDistribution(config Distribution) error {
	if config.AndroidAPK != "" {
		f, e := os.Stat(config.AndroidAPK)
		if e != nil || !f.Mode().IsRegular() || f.Size() == 0 || f.Size() > 300*1024*1024 {
			return errors.New("invalid Android APK")
		}
	}
	if config.IOSURL != "" {
		u, e := url.Parse(config.IOSURL)
		if e != nil || u.Scheme != "https" || u.Host == "" || u.User != nil {
			return errors.New("invalid iOS distribution URL")
		}
	}
	if config.AndroidCertificateSHA256 != "" && !regexp.MustCompile(`^[A-Fa-f0-9]{2}(:[A-Fa-f0-9]{2}){31}$`).MatchString(config.AndroidCertificateSHA256) {
		return errors.New("invalid certificate fingerprint")
	}
	if config.AppleApplicationID != "" && !regexp.MustCompile(`^[A-Z0-9]{10}\.app\.flint\.vpn$`).MatchString(config.AppleApplicationID) {
		return errors.New("invalid Apple application ID")
	}
	b.distribution = config
	return nil
}
func (b *Broker) webRequest(w http.ResponseWriter, r *http.Request) bool {
	if r.Method != "GET" && r.Method != "HEAD" {
		return false
	}
	path := r.URL.Path
	if path == Prefix+"distribution" {
		android := ""
		if b.distribution.AndroidAPK != "" {
			android = "https://flintmain.ru" + Prefix + "download/android"
		}
		write(w, 200, map[string]any{"androidUrl": android, "iosUrl": b.distribution.IOSURL})
		return true
	}
	if path == Prefix+"download/android" {
		if b.distribution.AndroidAPK == "" {
			http.NotFound(w, r)
			return true
		}
		w.Header().Set("Content-Type", "application/vnd.android.package-archive")
		w.Header().Set("Content-Disposition", `attachment; filename="Flint-Android.apk"`)
		w.Header().Set("X-Content-Type-Options", "nosniff")
		_ = http.NewResponseController(w).SetWriteDeadline(time.Time{})
		http.ServeFile(w, r, b.distribution.AndroidAPK)
		return true
	}
	if path == "/.well-known/assetlinks.json" {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "public, max-age=300")
		values := []any{}
		if b.distribution.AndroidCertificateSHA256 != "" {
			values = append(values, map[string]any{"relation": []string{"delegate_permission/common.handle_all_urls"}, "target": map[string]any{"namespace": "android_app", "package_name": "app.flint.vpn", "sha256_cert_fingerprints": []string{strings.ToUpper(b.distribution.AndroidCertificateSHA256)}}})
		}
		_ = json.NewEncoder(w).Encode(values)
		return true
	}
	if path == "/.well-known/apple-app-site-association" {
		details := []any{}
		if b.distribution.AppleApplicationID != "" {
			details = append(details, map[string]any{"appID": b.distribution.AppleApplicationID, "paths": []string{"/connect/*"}})
		}
		write(w, 200, map[string]any{"applinks": map[string]any{"apps": []any{}, "details": details}})
		return true
	}
	file := ""
	contentType := ""
	if strings.HasPrefix(path, "/connect/") {
		id := strings.TrimSuffix(strings.TrimPrefix(path, "/connect/"), "/")
		if !validID(id) {
			http.NotFound(w, r)
			return true
		}
		file = "web/index.html"
		contentType = "text/html; charset=utf-8"
	} else if path == Prefix+"web/connect.js" {
		file = "web/connect.js"
		contentType = "text/javascript; charset=utf-8"
	} else if path == Prefix+"web/connect.css" {
		file = "web/connect.css"
		contentType = "text/css; charset=utf-8"
	}
	if file == "" {
		return false
	}
	data, e := connectFiles.ReadFile(file)
	if e != nil {
		http.NotFound(w, r)
		return true
	}
	w.Header().Set("Content-Type", contentType)
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; base-uri 'none'; frame-ancestors 'none'; form-action 'none'")
	if r.Method == "GET" {
		_, _ = w.Write(data)
	}
	return true
}
