package view

import (
	"bytes"
	"encoding/json"
	"strings"
	"testing"
)

func TestPrettyJSONMatchesIndent(t *testing.T) {
	for _, src := range []string{
		`{}`, `[]`, `{"a":{},"b":[],"c":[{}]}`, ` {"s":"a \"q\" \\ {x} [y], z:" , "n":-1.5e3,"t":true,"f":false,"z":null} `,
		`[1,[2,[3,[4]]],{"k":{"k":{"k":"ü€😀"}}}]`, `"just a string"`, `42`,
		`{"items":[{"id":1,"name":"a","tags":["x","y"]},{"id":2,"name":"b","tags":[]}]}`,
	} {
		var want bytes.Buffer
		if err := json.Indent(&want, []byte(src), "", "  "); err != nil {
			t.Fatal(err)
		}
		var got bytes.Buffer
		if ok, err := PrettyTo(&got, []byte(src), "json"); !ok || err != nil {
			t.Fatal(ok, err)
		}
		if strings.TrimSpace(got.String()) != strings.TrimSpace(want.String()) {
			t.Fatalf("%s:\ngot\n%s\nwant\n%s", src, got.String(), want.String())
		}
	}
}

func TestPrettyMarkupAndCode(t *testing.T) {
	var b bytes.Buffer
	PrettyTo(&b, []byte(`<?xml version="1.0"?><a x="1>2"><b>text</b><c/><!-- c --><d><e>1</e></d></a>`), "xml")
	want := "<?xml version=\"1.0\"?>\n<a x=\"1>2\">\n  <b>text</b>\n  <c/>\n  <!-- c -->\n  <d>\n    <e>1</e>\n  </d>\n</a>"
	if b.String() != want {
		t.Fatalf("xml:\n%s", b.String())
	}
	b.Reset()
	PrettyTo(&b, []byte(`<!doctype html><html><head><meta charset="utf-8"><script>if(a<b){x()}</script></head><body><p>hi<br>there</p></body></html>`), "html")
	if !strings.Contains(b.String(), "    <meta charset=\"utf-8\">\n    <script>\n      if(a<b){x()}\n    </script>") {
		t.Fatalf("html:\n%s", b.String())
	}
	b.Reset()
	PrettyTo(&b, []byte(`function f(a){var s="}{;";if(a){return /[}]/.test(s)}else{return 1};for(;;){break}}`), "js")
	want = "function f(a) {\n  var s=\"}{;\";\n  if(a) {\n    return /[}]/.test(s)\n  } else {\n    return 1\n  };\n  for(;;) {\n    break\n  }\n}"
	if b.String() != want {
		t.Fatalf("js:\n%s", b.String())
	}
	b.Reset()
	PrettyTo(&b, []byte(`a{color:red;b:c}d{}`), "css")
	if b.String() != "a {\n  color:red;\n  b:c\n}\nd {\n}" {
		t.Fatalf("css:\n%q", b.String())
	}
}

func TestEncodeDecode(t *testing.T) {
	for _, enc := range []string{"gzip", "deflate", "br", "zstd", "gzip, br"} {
		e, err := Encode([]byte("hello hello hello"), enc)
		if err != nil {
			t.Fatal(enc, err)
		}
		d, err := Decode(e, enc)
		if err != nil || string(d) != "hello hello hello" {
			t.Fatalf("%s: %q %v", enc, d, err)
		}
	}
}
