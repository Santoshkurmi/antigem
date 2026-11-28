package cli

import (
	"context"
	"net/http"

	"antigem/netcap/internal/control"
	"antigem/netcap/internal/service"
	"antigem/netcap/internal/tlsx"
)

// controller is the Android side: the app's control channel (VPN tunnel, certificate, app lookups).
type controller interface {
	service.Platform
	CA() *tlsx.CA
	Attach(svc *service.Service)
	Routes() map[string]http.HandlerFunc
	Run(ctx context.Context)
}

func newController(ctx context.Context, name, token string) (controller, error) {
	return control.Dial(ctx, name, token, Version)
}
