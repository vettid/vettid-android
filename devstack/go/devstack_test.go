//go:build devenclave && integration && androiddevstack

// Package androiddevstack runs vettid-vault's integration stack as a
// long-lived DEVELOPMENT service for the Android app (ANDROID-PLAN A2): the
// real vettid-relay binary, LocalStack (S3, SQS, DynamoDB), vault-parent in
// TCP mode, a vault-enclave dev build (fake NSM and KMS, TEST-ONLY roots),
// the member API stand-in and a vaultctl peer vault.
//
// It is not part of vettid-vault. devstack/run.sh copies this file into a
// snapshot of vettid-vault (git archive of a commit) under
// devstack/android/, because it reuses that module's internal test packages
// (enclavetest, memberapitest, relaytest), and runs it with
//
//	go test -tags 'devenclave integration androiddevstack' -run TestDevStack -timeout 0 ./devstack/android/
//
// Everything listens on 127.0.0.1 only. The phone reaches it through
// `adb reverse`:
//
//	DEVSTACK_RELAY_PORT (18080)  the relay, plain HTTP (the app maps https://relay.vettid.test here)
//	DEVSTACK_API_PORT   (18081)  the member API stand-in ("Authorization: Bearer <user_guid>")
//	DEVSTACK_CTL_PORT   (18082)  dev control: trust anchors and the vaultctl peer
//
// The stack stops when DEVSTACK_STOP_FILE appears, or on SIGINT/SIGTERM.
// Every key here is TEST-ONLY (fixed public seeds).
package androiddevstack

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/http/httptest"
	"net/http/httputil"
	"net/url"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/dynamodb"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/sqs"

	"github.com/vettid/vettid-vault/enclave/awskms"
	"github.com/vettid/vettid-vault/internal/enclavetest"
	"github.com/vettid/vettid-vault/internal/memberapitest"
	"github.com/vettid/vettid-vault/internal/relaytest"
	"github.com/vettid/vettid-vault/parent"
	"github.com/vettid/vettid-vault/vms/manifest"
)

const (
	relayHost = "relay.vettid.test"
	relayURL  = "https://" + relayHost
	region    = enclavetest.KMSRegion
	kmsHost   = "kms." + region + ".amazonaws.com"
	gHost     = "android.googleapis.com"
	peerGUID  = "peer-vaultctl"
	peerPIN   = "24680135"
	release   = 3
)

func env(name, def string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return def
}

type stack struct {
	t        *testing.T
	dir      string
	logDir   string
	ep       string
	bucket   string
	db       *dynamodb.Client
	sqs      *sqs.Client
	s3       *s3.Client
	tables   memberapitest.Tables
	w        *enclavetest.World
	relayRaw string // http://127.0.0.1:port of the relay itself
	relayTLS string // the TLS front (relay.vettid.test) for the enclave and vaultctl
	kmsAddr  string
	gAddr    string
	ctlBin   string
	apiURL   string

	peerMu     sync.Mutex
	peerState  string
	peerEvents []map[string]any
}

func listen(t *testing.T, addr string) net.Listener {
	l, err := net.Listen("tcp", addr)
	if err != nil {
		t.Fatalf("listen %s: %v", addr, err)
	}
	return l
}

func freeAddr(t *testing.T) string {
	l := listen(t, "127.0.0.1:0")
	defer l.Close()
	return l.Addr().String()
}

func build(t *testing.T, out, tags, pkg string) {
	t.Helper()
	args := []string{"build", "-o", out}
	if tags != "" {
		args = append(args, "-tags", tags)
	}
	cmd := exec.Command("go", append(args, pkg)...)
	if b, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("build %s: %v\n%s", pkg, err, b)
	}
}

func tlsFront(t *testing.T, h http.Handler, h2 bool, names ...string) string {
	s := httptest.NewUnstartedServer(h)
	s.EnableHTTP2 = h2
	s.TLS = &tls.Config{Certificates: []tls.Certificate{enclavetest.ServerCert(names...)}}
	s.Config.ErrorLog = log.New(io.Discard, "", 0)
	s.StartTLS()
	t.Cleanup(s.Close)
	return s.Listener.Addr().String()
}

// serve runs h on a fixed 127.0.0.1 port.
func serve(t *testing.T, port string, h http.Handler) string {
	l := listen(t, "127.0.0.1:"+port)
	srv := &http.Server{Handler: h, ReadHeaderTimeout: 30 * time.Second, ErrorLog: log.New(io.Discard, "", 0)}
	go func() { _ = srv.Serve(l) }()
	t.Cleanup(func() { _ = srv.Close() })
	return "http://" + l.Addr().String()
}

func TestDevStack(t *testing.T) {
	ep := os.Getenv("VAULT_IT_LOCALSTACK")
	if ep == "" {
		t.Skip("VAULT_IT_LOCALSTACK not set (devstack/run.sh)")
	}
	s := &stack{t: t, ep: ep, dir: t.TempDir(), logDir: env("DEVSTACK_LOG_DIR", t.TempDir())}
	rb := make([]byte, 4)
	_, _ = rand.Read(rb)
	pfx := "ad" + hex.EncodeToString(rb)
	s.bucket = pfx + "-vault-data"
	qprefix := pfx + "-vault-control-"
	s.tables = memberapitest.Tables{Vaults: pfx + "-vaults", Instances: pfx + "-vault-instances", Requests: pfx + "-vault-requests", Releases: pfx + "-vault-releases"}

	parentBin, enclBin := filepath.Join(s.dir, "vault-parent"), filepath.Join(s.dir, "vault-enclave")
	s.ctlBin = filepath.Join(s.dir, "vaultctl")
	build(t, parentBin, "", "../../cmd/vault-parent")
	build(t, enclBin, "devenclave", "../../cmd/vault-enclave")
	build(t, s.ctlBin, "devenclave", "../../cmd/vaultctl")

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
	defer cancel()
	ac := aws.Config{Region: region, Credentials: credentials.NewStaticCredentialsProvider("test", "test", "")}
	s.s3 = s3.NewFromConfig(ac, func(o *s3.Options) { o.BaseEndpoint = aws.String(ep); o.UsePathStyle = true })
	if _, err := s.s3.CreateBucket(ctx, &s3.CreateBucketInput{Bucket: aws.String(s.bucket)}); err != nil {
		t.Fatalf("bucket: %v", err)
	}
	s.db = dynamodb.NewFromConfig(ac, func(o *dynamodb.Options) { o.BaseEndpoint = aws.String(ep) })
	s.sqs = sqs.NewFromConfig(ac, func(o *sqs.Options) { o.BaseEndpoint = aws.String(ep) })
	if err := memberapitest.CreateTables(ctx, s.db, s.tables); err != nil {
		t.Fatalf("tables: %v", err)
	}
	probe, err := s.sqs.CreateQueue(ctx, &sqs.CreateQueueInput{QueueName: aws.String(qprefix + "probe")})
	if err != nil {
		t.Fatalf("probe queue: %v", err)
	}
	urlPrefix := strings.TrimSuffix(*probe.QueueUrl, "probe")
	_, _ = s.sqs.DeleteQueue(ctx, &sqs.DeleteQueueInput{QueueUrl: probe.QueueUrl})

	s.w = enclavetest.NewWorld(time.Now, relayURL)
	s.w.SetSerial(uint64(time.Now().Unix()))
	s.w.AddRelease(enclavetest.Spec(release, "active"))
	if err := memberapitest.PutRelease(ctx, s.db, s.tables.Releases, enclavetest.Spec(release, "").PCR0Hex(), release, "active"); err != nil {
		t.Fatal(err)
	}

	// The relay (real binary), its TLS front for the enclave and vaultctl,
	// and a plain-HTTP front on a fixed port for the phone.
	r := relaytest.Start(t, relaytest.Options{"RELAY_BASE_URL": relayURL})
	target, _ := url.Parse(r.URL)
	proxy := &httputil.ReverseProxy{Rewrite: func(p *httputil.ProxyRequest) { p.SetURL(target); p.Out.Host = p.In.Host },
		FlushInterval: -1, ErrorLog: log.New(io.Discard, "", 0)}
	s.relayTLS = tlsFront(t, proxy, true, relayHost)
	s.relayRaw = serve(t, env("DEVSTACK_RELAY_PORT", "18080"), proxy)
	kms := enclavetest.NewKMSServer(s.w.KMS, region, awskms.Credentials{AccessKeyID: "test", SecretAccessKey: "test"})
	s.kmsAddr = tlsFront(t, kms, false, kmsHost)
	s.gAddr = tlsFront(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/attestation/status" {
			http.NotFound(w, r)
			return
		}
		_, _ = io.WriteString(w, `{"entries":{}}`)
	}), true, gHost)

	api := memberapitest.New(memberapitest.Config{DDB: s.db, SQS: s.sqs, Tables: s.tables, QueueURLPrefix: urlPrefix, Manifest: s.publishedManifest})
	s.apiURL = serve(t, env("DEVSTACK_API_PORT", "18081"), logged("api", api))

	// One instance of release 3: parent (TCP) + dev enclave.
	s.startInstance(pfx+"-a", qprefix, parentBin, enclBin)

	// The vaultctl peer: a second member's vault, enrolled through the API.
	s.peerState = filepath.Join(s.dir, "peer.json")
	s.mustVaultctl("init", "-role", "app", "-name", "vaultctl-peer", "-relay", relayURL)
	out := s.mustVaultctl("api-enroll", "-api", s.apiURL, "-guid", peerGUID, "-pin", peerPIN)
	t.Logf("peer vault enrolled: %s", strings.TrimSpace(out))

	ctlURL := serve(t, env("DEVSTACK_CTL_PORT", "18082"), logged("ctl", s.control()))
	ready := map[string]any{"relay_url": relayURL, "relay_transport": s.relayRaw, "api": s.apiURL, "ctl": ctlURL,
		"peer_guid": peerGUID, "started_at": time.Now().UTC().Format(time.RFC3339)}
	b, _ := json.MarshalIndent(ready, "", "  ")
	if f := os.Getenv("DEVSTACK_READY_FILE"); f != "" {
		if err := os.WriteFile(f, b, 0o600); err != nil {
			t.Fatal(err)
		}
	}
	fmt.Fprintf(os.Stderr, "devstack ready:\n%s\n", b)

	// Run until asked to stop.
	sig := make(chan os.Signal, 1)
	signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
	stop := os.Getenv("DEVSTACK_STOP_FILE")
	for {
		select {
		case <-sig:
			return
		case <-time.After(time.Second):
		}
		if stop != "" {
			if _, err := os.Stat(stop); err == nil {
				return
			}
		}
	}
}

// logged writes one line per request (method, path, status) to stderr:
// never bodies (envelopes) or headers (bearer guids).
func logged(name string, h http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rw := &statusWriter{ResponseWriter: w, status: 200}
		start := time.Now()
		h.ServeHTTP(rw, r)
		fmt.Fprintf(os.Stderr, "%s %s %s %s -> %d (%s)\n", time.Now().UTC().Format("15:04:05.000"), name, r.Method, r.URL.Path, rw.status,
			time.Since(start).Round(time.Millisecond))
	})
}

type statusWriter struct {
	http.ResponseWriter
	status int
}

func (s *statusWriter) WriteHeader(code int) {
	s.status = code
	s.ResponseWriter.WriteHeader(code)
}

func (s *stack) publishedManifest() []byte {
	doc := s.w.Served()
	sv, err := manifest.ParseServed(doc)
	if err != nil {
		panic(err)
	}
	if _, err := s.s3.PutObject(context.Background(), &s3.PutObjectInput{Bucket: aws.String(s.bucket),
		Key: aws.String(manifest.ObjectKey(manifest.SHA256Hex(sv.Manifest))), Body: bytes.NewReader(doc)}); err != nil {
		panic(err)
	}
	return doc
}

func (s *stack) startInstance(id, qprefix, parentBin, enclBin string) {
	t := s.t
	ctl, egr, health := freeAddr(t), freeAddr(t), freeAddr(t)
	args := []string{"-instance-id", id, "-region", region, "-bucket", s.bucket,
		"-table-vaults", s.tables.Vaults, "-table-instances", s.tables.Instances, "-table-requests", s.tables.Requests,
		"-queue-prefix", qprefix, "-relay-host", relayHost, "-control-tcp", ctl, "-egress-tcp", egr,
		"-aws-endpoint", s.ep, "-static-credentials", "-health", health, "-heartbeat", "10s", "-sweep", "-1s",
		"-resolve", relayHost + "=" + s.relayTLS, "-resolve", kmsHost + "=" + s.kmsAddr, "-resolve", gHost + "=" + s.gAddr}
	plog, err := os.Create(filepath.Join(s.logDir, "parent.log"))
	if err != nil {
		t.Fatal(err)
	}
	elog, err := os.Create(filepath.Join(s.logDir, "enclave.log"))
	if err != nil {
		t.Fatal(err)
	}
	p := exec.Command(parentBin, args...)
	p.Env = []string{"AWS_ACCESS_KEY_ID=test", "AWS_SECRET_ACCESS_KEY=test", "PATH=" + os.Getenv("PATH"), "GOMAXPROCS=2"}
	p.Stdout, p.Stderr = plog, plog
	if err := p.Start(); err != nil {
		t.Fatal(err)
	}
	e := exec.Command(enclBin, "-release", fmt.Sprint(release), "-control", ctl, "-egress", egr, "-relay-url", relayURL)
	e.Env = []string{"PATH=" + os.Getenv("PATH"), "GOMAXPROCS=2"}
	e.Stdout, e.Stderr = elog, elog
	if err := e.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = p.Process.Signal(syscall.SIGTERM)
		done := make(chan struct{})
		go func() { _ = p.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(60 * time.Second):
			_ = p.Process.Kill()
		}
		_ = e.Process.Signal(syscall.SIGTERM)
		done = make(chan struct{})
		go func() { _ = e.Wait(); close(done) }()
		select {
		case <-done:
		case <-time.After(30 * time.Second):
			_ = e.Process.Kill()
		}
	})
	deadline := time.Now().Add(90 * time.Second)
	for {
		resp, err := http.Get("http://" + health + "/healthz")
		if err == nil {
			var h parent.Health
			_ = json.NewDecoder(resp.Body).Decode(&h)
			resp.Body.Close()
			if h.OK {
				return
			}
		}
		if time.Now().After(deadline) {
			t.Fatalf("instance %s not healthy (logs in %s)", id, s.logDir)
		}
		time.Sleep(300 * time.Millisecond)
	}
}

// vaultctl runs the dev vaultctl on the peer's state file.
func (s *stack) vaultctl(args ...string) (string, error) {
	cmd := exec.Command(s.ctlBin, append([]string{"-state", s.peerState, "-timeout", "150s"}, args...)...)
	cmd.Env = []string{"PATH=" + os.Getenv("PATH"), "VAULTCTL_DEV_RESOLVE=" + relayHost + "=" + s.relayTLS, "GOMAXPROCS=2"}
	var out bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &out
	err := cmd.Run()
	return out.String(), err
}

func (s *stack) mustVaultctl(args ...string) string {
	s.t.Helper()
	out, err := s.vaultctl(args...)
	if err != nil {
		s.t.Fatalf("vaultctl %v: %v\n%s", args, err, out)
	}
	return out
}

// jsonValues decodes the JSON objects vaultctl printed.
func jsonValues(out string) []map[string]any {
	var vs []map[string]any
	i := strings.IndexByte(out, '{')
	if i < 0 {
		return nil
	}
	dec := json.NewDecoder(strings.NewReader(out[i:]))
	for {
		var v map[string]any
		if dec.Decode(&v) != nil {
			return vs
		}
		vs = append(vs, v)
	}
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func matches(body map[string]any, want map[string]string) bool {
	for k, v := range want {
		if fmt.Sprint(body[k]) != v {
			return false
		}
	}
	return true
}

// control serves the dev control API (TEST-ONLY): the stack's trust
// anchors, and the vaultctl peer.
func (s *stack) control() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /dev/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, 200, map[string]any{"ok": true})
	})
	// The TEST-ONLY trust of the dev stack: the test Nitro root (DER) and the
	// test manifest key (SPKI DER), which the app's instrumented tests pin.
	mux.HandleFunc("GET /dev/trust", func(w http.ResponseWriter, r *http.Request) {
		spki, err := x509.MarshalPKIXPublicKey(&enclavetest.ManifestKey().PublicKey)
		if err != nil {
			writeJSON(w, 500, map[string]any{"error": err.Error()})
			return
		}
		writeJSON(w, 200, map[string]any{
			"nitro_root":    base64.StdEncoding.EncodeToString(enclavetest.TestNitroCA().Root.Cert.Raw),
			"manifest_keys": []string{base64.StdEncoding.EncodeToString(spki)},
			"relay_url":     relayURL,
		})
	})
	// POST /dev/peer/request {"type": T, "body": {...}}: vaultctl request T BODY.
	mux.HandleFunc("POST /dev/peer/request", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Type string          `json:"type"`
			Body json.RawMessage `json:"body"`
		}
		if json.NewDecoder(io.LimitReader(r.Body, 1<<20)).Decode(&in) != nil || in.Type == "" {
			writeJSON(w, 400, map[string]any{"error": "bad_request"})
			return
		}
		body := string(in.Body)
		if body == "" || body == "null" {
			body = "{}"
		}
		s.peerMu.Lock()
		out, err := s.vaultctl("request", in.Type, body)
		s.peerMu.Unlock()
		vs := jsonValues(out)
		if err != nil || len(vs) == 0 {
			writeJSON(w, 502, map[string]any{"error": "vaultctl", "output": out})
			return
		}
		writeJSON(w, 200, vs[0])
	})
	// POST /dev/peer/event {"type": T, "match": {k: v}, "timeout_s": N}:
	// collects with vaultctl until a matching event arrives (events that
	// do not match are kept for later calls).
	mux.HandleFunc("POST /dev/peer/event", func(w http.ResponseWriter, r *http.Request) {
		var in struct {
			Type     string            `json:"type"`
			Match    map[string]string `json:"match"`
			TimeoutS int               `json:"timeout_s"`
		}
		if json.NewDecoder(io.LimitReader(r.Body, 1<<16)).Decode(&in) != nil || in.Type == "" {
			writeJSON(w, 400, map[string]any{"error": "bad_request"})
			return
		}
		if in.TimeoutS <= 0 || in.TimeoutS > 300 {
			in.TimeoutS = 90
		}
		deadline := time.Now().Add(time.Duration(in.TimeoutS) * time.Second)
		for {
			s.peerMu.Lock()
			for i, ev := range s.peerEvents {
				b, _ := ev["body"].(map[string]any)
				if ev["type"] == in.Type && matches(b, in.Match) {
					s.peerEvents = append(s.peerEvents[:i:i], s.peerEvents[i+1:]...)
					s.peerMu.Unlock()
					writeJSON(w, 200, ev)
					return
				}
			}
			if time.Now().After(deadline) || r.Context().Err() != nil {
				s.peerMu.Unlock()
				writeJSON(w, 404, map[string]any{"error": "timeout"})
				return
			}
			out, _ := s.vaultctl("events", "-wait", "3s")
			s.peerEvents = append(s.peerEvents, jsonValues(out)...)
			s.peerMu.Unlock()
		}
	})
	return mux
}
