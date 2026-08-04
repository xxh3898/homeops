package transport

import (
	"net/http"
	"testing"
)

func TestStatusErrorClassifiesValidationRejectionAsPermanent(t *testing.T) {
	t.Parallel()
	if !((StatusError{StatusCode: http.StatusUnprocessableEntity}).Permanent()) {
		t.Fatal("422 rejection must be permanent")
	}
}

func TestStatusErrorKeepsAuthenticationFailureRetryable(t *testing.T) {
	t.Parallel()
	if (StatusError{StatusCode: http.StatusUnauthorized}).Permanent() {
		t.Fatal("401 rejection must remain retryable after credential repair")
	}
}
