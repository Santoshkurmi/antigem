package redact

import (
	"strings"
	"testing"
)

func TestBodyHidesNestedAndText(t *testing.T) {
	in := `{"data":"{\"email\":\"a@b.com\",\"password\":\"hunter2\"}","json":{"password":"hunter2","token":"abc"},"note":"Bearer abcdefghijklmnopqrstuvwxyz"}`
	out := string(Body([]byte(in), "json"))
	if strings.Contains(out, "hunter2") || strings.Contains(out, `"abc"`) || strings.Contains(out, "abcdefghijklmnop") {
		t.Fatalf("secret left: %s", out)
	}
	if !strings.Contains(out, "a@b.com") {
		t.Fatalf("email should stay (privacy checks need it): %s", out)
	}
	txt := Text(`user=bob&password=hunter2&x=1 and {"api_key": "k-123456"} and \"token\":\"zz9\"`)
	for _, bad := range []string{"hunter2", "k-123456", "zz9"} {
		if strings.Contains(txt, bad) {
			t.Fatalf("%s left in %s", bad, txt)
		}
	}
	if SensitiveHeader("Access-Control-Allow-Credentials") || !SensitiveHeader("X-Api-Key") {
		t.Fatal("header rules wrong")
	}
	if q := Query("page=2&token=abc"); strings.Contains(q, "abc") || !strings.Contains(q, "page=2") {
		t.Fatal(q)
	}
}
