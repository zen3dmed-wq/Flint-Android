package pairing

import (
	"encoding/json"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestPublicDownloadsSupportRangeAndExposeOnlyConfiguredSoftware(t *testing.T) {
	dir := t.TempDir()
	apk := filepath.Join(dir, "Flint.apk")
	setup := filepath.Join(dir, "Flint.exe")
	for _, file := range []string{apk, setup} {
		if e := os.WriteFile(file, []byte("public-software-fixture"), 0600); e != nil {
			t.Fatal(e)
		}
	}
	b := New(nil, false)
	if e := b.ConfigureDistribution(Distribution{AndroidAPK: apk, WindowsSetup: setup}); e != nil {
		t.Fatal(e)
	}
	for _, platform := range []string{"android", "windows"} {
		r := httptest.NewRequest("GET", Prefix+"download/"+platform, nil)
		r.Header.Set("Range", "bytes=0-5")
		w := httptest.NewRecorder()
		b.ServeHTTP(w, r)
		if w.Code != 206 || w.Body.String() != "public" || w.Header().Get("Content-Range") != "bytes 0-5/23" {
			t.Fatalf("range %s: %d %q %s", platform, w.Code, w.Body.String(), w.Header().Get("Content-Range"))
		}
	}
	w := httptest.NewRecorder()
	b.ServeHTTP(w, httptest.NewRequest("GET", Prefix+"distribution", nil))
	var data map[string]string
	if json.Unmarshal(w.Body.Bytes(), &data) != nil || data["windowsUrl"] != "https://flintmain.ru"+Prefix+"download/windows" {
		t.Fatal("missing Windows installation URL")
	}
	w = httptest.NewRecorder()
	b.ServeHTTP(w, httptest.NewRequest("GET", Prefix+"download/../../secret", nil))
	if w.Code == 200 {
		t.Fatal("unconfigured file exposed")
	}
}
