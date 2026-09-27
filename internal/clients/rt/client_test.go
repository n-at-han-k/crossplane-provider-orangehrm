// Hand-written, and NOT generated: the generator writes client.go and the
// model_*.go beside it, never this file.
//
// What is checked is the handful of things in client.go that are decisions
// rather than plumbing -- the ones that fail silently against a real RT.
package rt

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

func TestAuthorizationHeader(t *testing.T) {
	for _, c := range []struct {
		name  string
		creds string
		want  string
	}{
		// RT's scheme is `token <key>`. A Bearer here authenticates as
		// nobody, and RT answers 200 with a login page rather than 401 --
		// which looks like success to everything downstream.
		{"token", `{"endpoint":"http://x","token":"abc"}`, "token abc"},
		{"apiKey verbatim", `{"endpoint":"http://x","apiKey":"token abc"}`, "token abc"},
		{"basic", `{"endpoint":"http://x","username":"root","password":"pw"}`, "Basic cm9vdDpwdw=="},
	} {
		t.Run(c.name, func(t *testing.T) {
			var got string
			srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				got = r.Header.Get("Authorization")
				w.Write([]byte(`{}`)) //nolint:errcheck // test server.
			}))
			defer srv.Close()

			client, err := NewClientFromCredentials([]byte(c.creds))
			if err != nil {
				t.Fatal(err)
			}
			client.BaseURL = srv.URL

			if _, err := client.DoRequest(context.Background(), "GET", "/rt", nil); err != nil {
				t.Fatal(err)
			}
			if got != c.want {
				t.Errorf("Authorization = %q, want %q", got, c.want)
			}
		})
	}
}

func TestIsNotFoundOnlyMatches404(t *testing.T) {
	// A 500 read as "gone" makes a controller create a second ticket and
	// orphan the first one, every time RT hiccups.
	for status, want := range map[int]bool{404: true, 500: false, 403: false} {
		srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(status)
		}))

		client := NewClient(srv.URL)
		_, err := client.DoRequest(context.Background(), "GET", "/ticket/1", nil)

		if err == nil {
			t.Fatalf("status %d: expected an error", status)
		}
		if IsNotFound(err) != want {
			t.Errorf("status %d: IsNotFound = %v, want %v", status, !want, want)
		}
		srv.Close()
	}
}

func TestIDFromLocation(t *testing.T) {
	for in, want := range map[string]string{
		"https://rt.example.com/REST/2.0/ticket/42": "42",
		"/REST/2.0/ticket/42/":                      "42",
		"42":                                        "42",
		"":                                          "",
	} {
		if got := IDFromLocation(in); got != want {
			t.Errorf("IDFromLocation(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestRTIDAcceptsBothOfRTsSpellings(t *testing.T) {
	// The bug this exists for: POST /asset answers "id":"5" while GET
	// /ticket/1 answers "id":1, and a model that commits to one of them
	// fails to parse the other. When that happens on a CREATE response the
	// resource exists in RT with nothing recording it here, and the next
	// reconcile makes another one. Seven assets became twenty-eight.
	var doc struct {
		Id    RTID `json:"id"`
		Inner []struct {
			Id RTID `json:"id"`
		} `json:"_hyperlinks"`
	}

	// Both spellings, and both in the same array -- which is exactly what a
	// ticket answers.
	body := `{"id":1,"_hyperlinks":[{"id":1},{"id":"2"},{"id":null}]}`
	if err := json.Unmarshal([]byte(body), &doc); err != nil {
		t.Fatal(err)
	}

	if doc.Id != "1" {
		t.Errorf("number id = %q, want \"1\"", doc.Id)
	}
	for i, want := range []RTID{"1", "2", ""} {
		if doc.Inner[i].Id != want {
			t.Errorf("_hyperlinks[%d].id = %q, want %q", i, doc.Inner[i].Id, want)
		}
	}

	// Out again as a string, which is what RT takes in a body.
	if out, err := json.Marshal(RTID("5")); err != nil || string(out) != `"5"` {
		t.Errorf("marshal = %s, %v; want \"5\"", out, err)
	}
}

func TestMissingEndpointIsRefused(t *testing.T) {
	// Every request would go to a relative URL and fail somewhere far from
	// the ProviderConfig that is actually wrong.
	if _, err := NewClientFromCredentials([]byte(`{"token":"abc"}`)); err == nil {
		t.Error("expected credentials with no endpoint to be refused")
	}
}
