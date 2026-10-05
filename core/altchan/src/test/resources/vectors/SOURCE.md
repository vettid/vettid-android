# Recovery QR vectors (generated, do not edit)

`recovery-qr.json` was generated with vettid-vault's own code, not copied from
its testdata (vettid-vault has no recovery-QR vector file): a scratch Go program
outside the vettid-vault checkout, with `replace github.com/vettid/vettid-vault =>`
a checkout at commit ca10a728427f84ad8ba34466877c3b6a90a51cb0 (VAULT-MESSAGING
0.10.5). It builds every `valid` payload with `vms/altchan.RecoveryQR` and checks
that `ParseRecoveryQR` reads it back; every `invalid` payload is one that
`ParseRecoveryQR` rejects; `transfer` is a `vms/invite.QR` of kind `p` (the
direct-transfer QR, §6.7.1) with its link. `RecoveryQrVectorsTest` checks the
app against all of them.

`sha256sum`:

```
99829e63d3f80962b21413771de2c6de301c4633fcd8c976ee2614afffbd4189  recovery-qr.json
```

The generator (`go run .` in a module whose go.mod requires vettid-vault with the
replace above, output to recovery-qr.json):

```go
// Generates the recovery-QR and transfer-link vectors for vettid-android
// with vettid-vault's own code (vms/altchan.RecoveryQR / ParseRecoveryQR,
// vms/invite.QR). Deterministic: fixed bytes, no randomness.
package main

import (
	"crypto/sha256"
	"encoding/base32"
	"encoding/json"
	"fmt"
	"os"

	"github.com/vettid/vettid-vault/vms/altchan"
	"github.com/vettid/vettid-vault/vms/invite"
)

var codeEnc = base32.NewEncoding("0123456789ABCDEFGHJKMNPQRSTVWXYZ").WithPadding(base32.NoPadding)

type valid struct {
	Name       string `json:"name"`
	QR         string `json:"qr"`
	VaultID    string `json:"vault_id"`
	RecoveryID string `json:"recovery_id"`
	Code       string `json:"code"`
	Grouped    string `json:"grouped"`
}

type invalid struct {
	Name string `json:"name"`
	QR   string `json:"qr"`
}

func group(c string) string {
	s := ""
	for i := 0; i < len(c); i += 4 {
		if i > 0 {
			s += " "
		}
		s += c[i : i+4]
	}
	return s
}

func main() {
	out := map[string]any{"source": "vettid-vault ca10a72 vms/altchan RecoveryQR/ParseRecoveryQR, vms/invite QR (scratch generator, not in vettid-vault)"}
	var vs []valid
	ids := []struct{ name, vid, rid string }{
		{"hex_vault_id", "0123456789abcdef0123456789abcdef", "01JA0RECVERY0000000000001X"},
		{"dev_vault_id", "vault-01JABCDEFGHJKMNPQRSTVWXYZ", "01K6ZZZZZZZZZZZZZZZZZZZZZZ"},
		{"all_zero_code", "v", "00000000000000000000000000"},
	}
	for i, id := range ids {
		raw := sha256.Sum256([]byte(fmt.Sprintf("vettid-android recovery vector %d", i)))
		code := codeEnc.EncodeToString(raw[:20])
		if id.name == "all_zero_code" {
			code = codeEnc.EncodeToString(make([]byte, 20))
		}
		qr := altchan.RecoveryQR(&altchan.RecoveryCode{VaultID: id.vid, RecoveryID: id.rid, Code: code})
		p, err := altchan.ParseRecoveryQR(qr)
		if err != nil || p.Code != code || p.VaultID != id.vid || p.RecoveryID != id.rid {
			panic(fmt.Sprintf("round trip %s: %v", id.name, err))
		}
		vs = append(vs, valid{id.name, string(qr), id.vid, id.rid, code, group(code)})
	}
	out["valid"] = vs
	code := vs[0].Code
	bad := []invalid{
		{"wrong_type", `{"v":1,"t":"p","vault_id":"v","recovery_id":"01JA0RECVERY0000000000001X","code":"` + code + `"}`},
		{"wrong_version", `{"v":2,"t":"r","vault_id":"v","recovery_id":"01JA0RECVERY0000000000001X","code":"` + code + `"}`},
		{"short_code", `{"v":1,"t":"r","vault_id":"v","recovery_id":"01JA0RECVERY0000000000001X","code":"` + code[:31] + `"}`},
		{"bad_ulid", `{"v":1,"t":"r","vault_id":"v","recovery_id":"not-a-ulid","code":"` + code + `"}`},
		{"empty_vault_id", `{"v":1,"t":"r","vault_id":"","recovery_id":"01JA0RECVERY0000000000001X","code":"` + code + `"}`},
		{"missing_code", `{"v":1,"t":"r","vault_id":"v","recovery_id":"01JA0RECVERY0000000000001X"}`},
		{"duplicate_member", `{"v":1,"t":"r","t":"r","vault_id":"v","recovery_id":"01JA0RECVERY0000000000001X","code":"` + code + `"}`},
		{"not_json", code},
	}
	for _, b := range bad {
		if _, err := altchan.ParseRecoveryQR([]byte(b.QR)); err == nil {
			panic("accepted " + b.Name)
		}
	}
	out["invalid"] = bad
	// The transfer QR (§6.7.1): the pairing QR with t = "p".
	h := sha256.Sum256([]byte("blob"))
	key := sha256.Sum256([]byte("k_b"))
	q := &invite.QR{Kind: invite.KindApp, Relay: "https://relay.vettid.test", ClaimID: "abcdefghijklmnopqrstuvwxyz", Hash: h, Key: key[:], Exp: 1791100000}
	qb, err := q.Marshal()
	if err != nil {
		panic(err)
	}
	link, err := q.Link()
	if err != nil {
		panic(err)
	}
	out["transfer"] = map[string]any{"qr": string(qb), "link": link, "exp": q.Exp}
	e := json.NewEncoder(os.Stdout)
	e.SetIndent("", "  ")
	e.SetEscapeHTML(false)
	_ = e.Encode(out)
}
```
