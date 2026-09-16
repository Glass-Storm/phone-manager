package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"strings"

	ecosysv1 "phone-manager/tools/mockpeer/gen/ecosys/v1"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// tokenFileMode is read/write for the owner only: the bearer token is an
// ephemeral loopback test secret and must not be world-readable.
const tokenFileMode = 0o600

// The three scenarios the CLI can run.
const (
	scenarioFull   = "full"
	scenarioPair   = "pair"
	scenarioStream = "stream"
)

// The media selectors.
const (
	modeAudio = "audio"
	modeVideo = "video"
	modeBoth  = "both"
)

// errUnauthenticated marks a run that was legitimately refused by the hub's auth
// gate. main maps it to the machine-checkable `UNAUTHENTICATED` line + exit 2.
var errUnauthenticated = errors.New("unauthenticated")

// pairRejectedError carries the hub's typed rejection reason for a bad PIN.
type pairRejectedError struct{ reason string }

func (e *pairRejectedError) Error() string { return "pair rejected: " + e.reason }

// scenarioResult reports what a run accomplished: media frames SENT and
// transcript frames RECEIVED.
type scenarioResult struct {
	frames      int
	transcripts int
}

// runScenario drives the requested RPC sequence and returns the counts a caller
// renders as the machine-checkable stdout line.
//
// Plaintext only: the LAN hub is cleartext by design and TLS on Android is the
// known-broken path, so no credentials beyond insecure are configured.
func runScenario(ctx context.Context, cfg config) (scenarioResult, error) {
	conn, err := grpc.NewClient(cfg.addr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return scenarioResult{}, fmt.Errorf("dial %s: %w", cfg.addr, err)
	}
	defer conn.Close()

	pairing := ecosysv1.NewPairingServiceClient(conn)

	if cfg.noToken {
		// Deliberately unauthenticated: the hub MUST refuse and say so.
		if err := heartbeat(ctx, pairing, ""); err != nil {
			return scenarioResult{}, err
		}
		return scenarioResult{}, errors.New("hub accepted a heartbeat with no token")
	}

	token, err := resolveToken(ctx, pairing, cfg)
	if err != nil {
		return scenarioResult{}, err
	}

	if cfg.expectUnaut {
		// The revoked-token leg: the token was valid at Pair time and has since
		// been revoked, so the hub MUST now refuse. A success here is the bug.
		if err := heartbeat(ctx, pairing, token); err != nil {
			return scenarioResult{}, err
		}
		return scenarioResult{}, errors.New("hub accepted a REVOKED token")
	}

	if err := heartbeat(ctx, pairing, token); err != nil {
		return scenarioResult{}, err
	}
	if cfg.scenario == scenarioPair {
		return scenarioResult{}, nil
	}

	return openStream(ctx, conn, cfg, token)
}

// resolveToken picks the bearer credential from the configured source: a
// pre-issued token file (the post-revoke leg), a literal token, or a fresh Pair.
// A freshly issued token is also written to --token-out when requested, which is
// the only sanctioned way for it to leave the process.
func resolveToken(ctx context.Context, pairing ecosysv1.PairingServiceClient, cfg config) (string, error) {
	if cfg.tokenFile != "" {
		raw, err := os.ReadFile(cfg.tokenFile)
		if err != nil {
			return "", fmt.Errorf("read token file %s: %w", cfg.tokenFile, err)
		}
		token := strings.TrimSpace(string(raw))
		if token == "" {
			return "", fmt.Errorf("token file %s was empty", cfg.tokenFile)
		}
		return token, nil
	}
	if cfg.token != "" {
		return cfg.token, nil
	}

	token, err := pair(ctx, pairing, cfg.pin)
	if err != nil {
		return "", err
	}
	if cfg.tokenOut != "" {
		if err := os.WriteFile(cfg.tokenOut, []byte(token+"\n"), tokenFileMode); err != nil {
			return "", fmt.Errorf("write token file %s: %w", cfg.tokenOut, err)
		}
	}
	return token, nil
}

// pair redeems the window PIN. A rejection is expected (bad/expired/replayed
// PIN), so it is returned as a typed error rather than a transport failure.
func pair(ctx context.Context, client ecosysv1.PairingServiceClient, pin string) (string, error) {
	resp, err := client.Pair(ctx, &ecosysv1.PairRequest{
		Pin:        pin,
		DeviceName: "mockpeer",
		Role:       ecosysv1.DeviceRole_DEVICE_ROLE_GLASS,
	})
	if err != nil {
		return "", mapStatus(err)
	}
	if !resp.GetOk() {
		return "", &pairRejectedError{reason: resp.GetRejectReason()}
	}
	if resp.GetToken() == "" {
		return "", errors.New("hub reported ok but issued an empty token")
	}
	return resp.GetToken(), nil
}

// heartbeat proves liveness with (or without) a bearer token. An empty token
// sends NO metadata, which is exactly the `--no-token` rejection case.
func heartbeat(ctx context.Context, client ecosysv1.PairingServiceClient, token string) error {
	callCtx := ctx
	if token != "" {
		callCtx = metadata.AppendToOutgoingContext(ctx, "authorization", "Bearer "+token)
	}
	resp, err := client.Heartbeat(callCtx, &ecosysv1.HeartbeatRequest{})
	if err != nil {
		return mapStatus(err)
	}
	if !resp.GetOk() {
		return errors.New("hub refused heartbeat without a status")
	}
	return nil
}

// openStream pushes synthetic media and drains result frames until the hub
// closes the stream, counting the transcripts it produced.
func openStream(ctx context.Context, conn *grpc.ClientConn, cfg config, token string) (scenarioResult, error) {
	callCtx := metadata.AppendToOutgoingContext(ctx, "authorization", "Bearer "+token)
	stream, err := ecosysv1.NewStreamServiceClient(conn).OpenStream(callCtx)
	if err != nil {
		return scenarioResult{}, mapStatus(err)
	}

	sent := 0
	for i := 0; i < cfg.frames; i++ {
		if cfg.hasAudio() {
			frame := &ecosysv1.StreamFrame{
				Payload: &ecosysv1.StreamFrame_AudioPcm16_16K{AudioPcm16_16K: SyntheticAudioFrame(i)},
			}
			if err := stream.Send(frame); err != nil {
				return scenarioResult{frames: sent}, mapStatus(err)
			}
			sent++
		}
		if cfg.hasVideo() {
			frame := &ecosysv1.StreamFrame{
				Payload: &ecosysv1.StreamFrame_VideoH264Nal{VideoH264Nal: SyntheticVideoNAL(i)},
			}
			if err := stream.Send(frame); err != nil {
				return scenarioResult{frames: sent}, mapStatus(err)
			}
			sent++
		}
	}

	if err := stream.CloseSend(); err != nil {
		return scenarioResult{frames: sent}, mapStatus(err)
	}

	transcripts := 0
	for {
		resp, err := stream.Recv()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return scenarioResult{frames: sent, transcripts: transcripts}, mapStatus(err)
		}
		if resp.GetTranscript() != "" {
			transcripts++
		}
	}
	return scenarioResult{frames: sent, transcripts: transcripts}, nil
}

// mapStatus converts a gRPC transport failure into either the
// errUnauthenticated sentinel (the expected refusal) or a descriptive error.
func mapStatus(err error) error {
	if status.Code(err) == codes.Unauthenticated {
		return fmt.Errorf("%w: %s", errUnauthenticated, status.Convert(err).Message())
	}
	return err
}
