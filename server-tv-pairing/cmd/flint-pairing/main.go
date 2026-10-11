package main

import (
	"context"
	"flag"
	pairing "flint/pairing"
	"fmt"
	"net/http"
	"os"
	"os/signal"
	"time"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:8099", "private reverse-proxy listener")
	upstream := flag.String("upstream", "https://flintmain.ru/api/v1", "authoritative HTTPS account API")
	proxy := flag.Bool("trust-loopback-proxy", false, "trust overwritten X-Real-IP only from loopback reverse proxy")
	apk := flag.String("android-apk", "", "public phone APK file for QR installation page")
	cert := flag.String("android-certificate-sha256", "", "colon-separated release certificate fingerprint for Android App Links")
	ios := flag.String("ios-url", "", "HTTPS App Store or TestFlight URL, empty until published")
	apple := flag.String("apple-application-id", "", "Apple Team ID.app.flint.vpn, empty until signed")
	flag.Parse()
	auth, err := pairing.NewAPIAuthorizer(*upstream)
	if err != nil {
		fmt.Fprintln(os.Stderr, "Invalid upstream configuration")
		os.Exit(1)
	}
	handler := pairing.New(auth, *proxy)
	if err := handler.ConfigureDistribution(pairing.Distribution{AndroidAPK: *apk, IOSURL: *ios, AndroidCertificateSHA256: *cert, AppleApplicationID: *apple}); err != nil {
		fmt.Fprintln(os.Stderr, "Invalid distribution configuration")
		os.Exit(1)
	}
	server := &http.Server{Addr: *listen, Handler: handler, ReadHeaderTimeout: 5 * time.Second, ReadTimeout: 20 * time.Second, WriteTimeout: 30 * time.Second, IdleTimeout: 30 * time.Second, MaxHeaderBytes: 16 * 1024}
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt)
	defer stop()
	go func() {
		<-ctx.Done()
		end, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = server.Shutdown(end)
	}()
	fmt.Println("Flint device pairing relay", *listen, "(no account tokens or subscription URLs logged)")
	if err := server.ListenAndServe(); err != nil && err != http.ErrServerClosed {
		fmt.Fprintln(os.Stderr, "Listener stopped:", err)
		os.Exit(1)
	}
}
