// The one runnable check on the hand-written half of this client: the
// generated code never exercises the token exchange, and a provider that
// cannot mint a token does nothing at all.
//
//	go test ./internal/clients/orangehrm/

package orangehrm

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"
)

// The cache is process-wide, so a test that does not clear it reads another
// test's token.
func reset() {
	tokensMu.Lock()
	tokens = map[string]token{}
	tokensMu.Unlock()
}

func credentials(t *testing.T, endpoint string, extra map[string]string) *Client {
	t.Helper()

	creds := map[string]string{
		"endpoint":     endpoint,
		"clientId":     "crossplane",
		"clientSecret": "shh",
	}
	for k, v := range extra {
		creds[k] = v
	}

	raw, err := json.Marshal(creds)
	if err != nil {
		t.Fatal(err)
	}

	client, err := NewClientFromCredentials(raw)
	if err != nil {
		t.Fatal(err)
	}

	return client
}

func TestDeleteIDs(t *testing.T) {
	// Numbers, not strings: the schema says `integer` and OrangeHRM refuses a
	// quoted id.
	raw, err := json.Marshal(DeleteIDs("7", "not-a-number"))
	if err != nil {
		t.Fatal(err)
	}

	if got, want := string(raw), `{"ids":[7,"not-a-number"]}`; got != want {
		t.Fatalf("DeleteIDs: got %s, want %s", got, want)
	}
}

func TestTokenIsMintedOnceAndSent(t *testing.T) {
	reset()

	mints, calls := 0, 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/oauth2/token" {
			mints++
			if grant := r.FormValue("grant_type"); grant != "password" {
				t.Errorf("grant_type: got %q, want password", grant)
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"access_token":"minted","expires_in":3600}`))
			return
		}

		calls++
		if got := r.Header.Get("Authorization"); got != "Bearer minted" {
			t.Errorf("Authorization: got %q, want Bearer minted", got)
		}
		_, _ = w.Write([]byte(`{"data":{"id":1},"meta":{}}`))
	}))
	defer server.Close()

	client := credentials(t, server.URL, map[string]string{"username": "admin", "password": "pw"})

	for range 2 {
		if _, err := client.DoRequest(context.Background(), "GET", "/api/v2/admin/educations/1", nil); err != nil {
			t.Fatal(err)
		}
	}

	if mints != 1 {
		t.Fatalf("minted %d tokens for two requests, want 1", mints)
	}
	if calls != 2 {
		t.Fatalf("made %d API calls, want 2", calls)
	}
}

func TestA401MintsAgainAndRetriesOnce(t *testing.T) {
	reset()

	mints, refusals := 0, 0
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/oauth2/token" {
			mints++
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"access_token":"fresh","expires_in":3600}`))
			return
		}

		// The first call is refused, as a restarted OrangeHRM refuses a token
		// this process still thinks is valid.
		if refusals == 0 {
			refusals++
			w.WriteHeader(http.StatusUnauthorized)
			return
		}

		_, _ = w.Write([]byte(`{"data":{"id":1},"meta":{}}`))
	}))
	defer server.Close()

	client := credentials(t, server.URL, nil)

	body, err := client.DoRequest(context.Background(), "GET", "/api/v2/admin/educations/1", nil)
	if err != nil {
		t.Fatalf("a 401 should have been retried with a new token: %v", err)
	}
	if string(body) != `{"data":{"id":1},"meta":{}}` {
		t.Fatalf("body: got %s", body)
	}
	if mints != 2 {
		t.Fatalf("minted %d tokens, want 2 -- the refused one should have been dropped", mints)
	}
}

func TestNotFoundIsTheOnlyGone(t *testing.T) {
	reset()

	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/oauth2/token" {
			_, _ = w.Write([]byte(`{"access_token":"minted","expires_in":3600}`))
			return
		}
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	client := credentials(t, server.URL, nil)

	_, err := client.DoRequest(context.Background(), "GET", "/api/v2/admin/educations/1", nil)
	if err == nil {
		t.Fatal("a 500 should be an error")
	}
	// Treating a 500 as "gone" is what makes a controller create a second
	// resource and orphan the first.
	if IsNotFound(err) {
		t.Fatalf("a 500 reported as not found: %v", err)
	}
}

func TestCredentialsNeedAnEndpointAndACredential(t *testing.T) {
	for _, raw := range []string{`{}`, `{"endpoint":"https://hr.example.com/web/index.php"}`, `not json`} {
		if _, err := NewClientFromCredentials([]byte(raw)); err == nil {
			t.Fatalf("accepted %s", raw)
		}
	}
}
