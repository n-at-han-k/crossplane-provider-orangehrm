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

// The first Observe of every resource asks about the external name
// crossplane-runtime seeded from the Kubernetes NAME, and OrangeHRM answers a
// non-numeric id 422 Invalid Parameter rather than 404 on the endpoints that
// validate it. A 422 is not "does not exist", so Observe would fail and Create
// would never run -- which is why what counts as an id is decided here rather
// than from the response.
func TestOnlyANumberCanBeAnID(t *testing.T) {
	for _, id := range []string{"1", "7", "12345"} {
		if !IsID(id) {
			t.Errorf("IsID(%q) = false, want true", id)
		}
	}
	for _, id := range []string{"", "head-office", "annual-leave", "7a", " 7"} {
		if IsID(id) {
			t.Errorf("IsID(%q) = true, want false -- nothing has been created yet", id)
		}
	}
}

// PHP's json_encode writes an empty associative array as `[]`, and OrangeHRM's
// envelope does exactly that -- {"data":{…},"meta":[]} -- while the document
// says `meta` is an object. Typed as a map it fails to unmarshal, which makes
// the WHOLE response unparseable: for a create, the record then exists with
// nothing recording it and the next reconcile makes another. It made ten
// identical locations before this was fixed.
//
// GetALocation200Response is generated, so a rename here is a real signal
// rather than a broken test.
func TestAnEmptyMetaIsNotAnObject(t *testing.T) {
	var envelope GetALocation200Response

	if err := json.Unmarshal([]byte(`{"data":{"id":7,"name":"Head Office"},"meta":[]}`), &envelope); err != nil {
		t.Fatalf("an empty meta should parse: %v", err)
	}
	if envelope.Data.Id != 7 {
		t.Fatalf("data lost: %+v", envelope.Data)
	}
}

// The identifier has to come out of a create's answer even when the rest of it
// cannot be parsed, because the row is already written: a holiday answers
// "length": 0 where the description says a string, and losing the id to that is
// a second row on the next reconcile.
func TestCreatedIDSurvivesAnAnswerThatDoesNotFitTheDocument(t *testing.T) {
	body := []byte(`{"data":{"id":7,"name":"Good Friday","length":0,"lengthName":"Full Day"},"meta":[]}`)

	if got := CreatedID(body); got != "7" {
		t.Fatalf("CreatedID = %q, want 7", got)
	}
	// Quoted, as some endpoints answer.
	if got := CreatedID([]byte(`{"data":{"id":"12"},"meta":[]}`)); got != "12" {
		t.Fatalf("CreatedID = %q, want 12", got)
	}
	// Nothing to find: the caller falls back to the Location header.
	if got := CreatedID([]byte(`{"data":[],"meta":[]}`)); got != "" {
		t.Fatalf("CreatedID = %q, want empty", got)
	}
}

func TestLooseStringTakesANumberOrAString(t *testing.T) {
	var holiday struct {
		Length      LooseString `json:"length"`
		HoursPerDay LooseString `json:"hoursPerDay"`
		Missing     LooseString `json:"missing"`
	}

	if err := json.Unmarshal([]byte(`{"length":0,"hoursPerDay":"8.00","missing":null}`), &holiday); err != nil {
		t.Fatal(err)
	}
	if holiday.Length != "0" || holiday.HoursPerDay != "8.00" || holiday.Missing != "" {
		t.Fatalf("got %+v", holiday)
	}

	// Out as a string, which is what every endpoint taking one accepts.
	raw, err := json.Marshal(holiday)
	if err != nil {
		t.Fatal(err)
	}
	if string(raw) != `{"length":"0","hoursPerDay":"8.00","missing":""}` {
		t.Fatalf("marshalled %s", raw)
	}
}
