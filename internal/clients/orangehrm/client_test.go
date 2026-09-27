// The one runnable check on the hand-written half of this client: the
// generated code never exercises how a credential is read or what counts as a
// failure, and both are how a provider silently does nothing.
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

// The credential OrangeHRM itself issues is a bare token, and the Secret
// holding one was made by whatever provisioned the instance. Requiring a JSON
// document around it would mean copying that Secret into one.
func TestCredentialsAreATokenOrADocument(t *testing.T) {
	bare, err := NewClientFromCredentials([]byte("  a-long-lived-token\n"), "https://hr.example.com/web/index.php/")
	if err != nil {
		t.Fatal(err)
	}
	if bare.Token != "a-long-lived-token" {
		t.Fatalf("bare token: got %q", bare.Token)
	}
	// Trailing slash off the endpoint, or every path would double it.
	if bare.BaseURL != "https://hr.example.com/web/index.php" {
		t.Fatalf("endpoint: got %q", bare.BaseURL)
	}

	document, err := NewClientFromCredentials(
		[]byte(`{"endpoint":"https://from-secret.example.com","token":"t"}`), "")
	if err != nil {
		t.Fatal(err)
	}
	if document.BaseURL != "https://from-secret.example.com" {
		t.Fatalf("endpoint from document: got %q", document.BaseURL)
	}

	// spec.endpoint wins, so the ProviderConfig is where the instance is named
	// even when the secret says something stale.
	both, err := NewClientFromCredentials(
		[]byte(`{"endpoint":"https://stale.example.com","token":"t"}`), "https://live.example.com")
	if err != nil {
		t.Fatal(err)
	}
	if both.BaseURL != "https://live.example.com" {
		t.Fatalf("spec.endpoint should win: got %q", both.BaseURL)
	}
}

func TestCredentialsNeedAnEndpointAndAToken(t *testing.T) {
	if _, err := NewClientFromCredentials([]byte("a-token"), ""); err == nil {
		t.Fatal("accepted a token with nowhere to send it")
	}
	if _, err := NewClientFromCredentials([]byte(`{"endpoint":"https://hr.example.com"}`), ""); err == nil {
		t.Fatal("accepted a document with no token")
	}
}

func TestTheTokenIsSentAsABearer(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get("Authorization"); got != "Bearer a-token" {
			t.Errorf("Authorization: got %q, want Bearer a-token", got)
		}
		_, _ = w.Write([]byte(`{"data":{"id":1},"meta":{}}`))
	}))
	defer server.Close()

	client, err := NewClientFromCredentials([]byte("a-token"), server.URL)
	if err != nil {
		t.Fatal(err)
	}

	if _, err := client.DoRequest(context.Background(), "GET", "/api/v2/admin/educations/1", nil); err != nil {
		t.Fatal(err)
	}
}

// The failure this API actually has: an unrecognised token is answered with a
// redirect to the login page, which answers 200 with HTML. Followed, that is a
// parse error somewhere far from the cause.
func TestARedirectToLoginIsAnErrorRatherThanHTML(t *testing.T) {
	login := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/auth/login" {
			_, _ = w.Write([]byte("<html>login</html>"))
			return
		}
		http.Redirect(w, r, "/auth/login", http.StatusFound)
	}))
	defer login.Close()

	client, err := NewClientFromCredentials([]byte("stale-token"), login.URL)
	if err != nil {
		t.Fatal(err)
	}

	_, err = client.DoRequest(context.Background(), "GET", "/api/v2/admin/educations/1", nil)
	if err == nil {
		t.Fatal("a redirect to the login page was reported as success")
	}
	if IsNotFound(err) {
		t.Fatalf("a redirect reported as not found: %v", err)
	}
}

func TestNotFoundIsTheOnlyGone(t *testing.T) {
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, _ *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer server.Close()

	client, err := NewClientFromCredentials([]byte("a-token"), server.URL)
	if err != nil {
		t.Fatal(err)
	}

	_, err = client.DoRequest(context.Background(), "GET", "/api/v2/admin/educations/1", nil)
	if err == nil {
		t.Fatal("a 500 should be an error")
	}
	// Treating a 500 as "gone" is what makes a controller create a second
	// resource and orphan the first.
	if IsNotFound(err) {
		t.Fatalf("a 500 reported as not found: %v", err)
	}
}
