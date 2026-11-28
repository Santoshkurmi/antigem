package query

import (
	"testing"
	"time"

	"antigem/netcap/internal/flow"
)

func TestFilters(t *testing.T) {
	f := &flow.Flow{ID: 42, Kind: flow.KindHTTP, App: "com.myapp", Host: "api.myapp.dev", Scheme: "https", Port: 443,
		Method: "POST", Path: "/v1/users/17", Query: "token=abc&page=2", HTTPVersion: "2", Status: 401, TLS: "decrypted",
		Start: time.Now().Add(-2 * time.Minute), Res: flow.Body{Type: "json", Size: 2048}, Timings: flow.Timings{Total: 800},
		ReqHeaders: flow.Headers{{"Authorization", "Bearer x"}, {"User-Agent", "okhttp/4.12"}}, Tags: []string{"login"}}
	cases := map[string]bool{
		"":                                     true,
		"host:api.myapp.dev":                   true,
		"host:*.myapp.dev":                     true,
		"host:other.dev":                       false,
		"path:/v1/users/*":                     true,
		"path:/v1/users/":                      false,
		"method:post status:4xx":               true,
		"status:200-299":                       false,
		"status:>=400":                         true,
		"type:json,html":                       true,
		"-type:json":                           false,
		"size>1k time>500ms":                   true,
		"size>10k":                             false,
		"since:10m":                            true,
		"since:1m":                             false,
		"after:f41 before:f43":                 true,
		"tag:login":                            true,
		"req.header.authorization:exists":      true,
		"req.header.user-agent:okhttp*":        true,
		"res.header.x-none:missing":            true,
		"http:2 tls:decrypted kind:http":       true,
		"users":                                true,
		"url:/token=\\w+/":                     true,
		"url:~page=[0-9]":                      true,
		"(status:200 OR status:401) app:com.*": true,
		"status:200 OR host:x.dev":             false,
		"noise:false":                          true,
	}
	for src, want := range cases {
		flt, err := Parse(src)
		if err != nil {
			t.Fatalf("%q: %v", src, err)
		}
		if got := flt.Match(f, nil); got != want {
			t.Errorf("%q: got %v want %v", src, got, want)
		}
	}
	if _, err := Parse("bogus:1"); err == nil {
		t.Error("unknown field should fail")
	}
	flt := MustParse("host:*.myapp.dev status:4xx since:5m")
	if where, args := flt.SQL(); where == "1" || len(args) != 4 {
		t.Errorf("pushdown: %s %v", where, args)
	}
}
