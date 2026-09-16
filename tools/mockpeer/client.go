package main

import (
	"context"
	"errors"
	"fmt"
	"io"

	ecosysv1 "phone-manager/tools/mockpeer/gen/ecosys/v1"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

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

// runScenario drives the requested RPC sequence and returns the number of media
// frames SENT and the number of transcript frames RECEIVED.
//
// Plaintext only: the LAN hub is cleartext by design and TLS on Android is the
// known-broken path, so no credentials beyond insecure are configured.
func runScenario(ctx context.Context, cfg config) (int, int, error) {
	conn, err := grpc.NewClient(cfg.addr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return 0, 0, fmt.Errorf("dial %s: %w", cfg.addr, err)
	}
	defer conn.Close()

	pairing := ecosysv1.NewPairingServiceClient(conn)

	if cfg.noToken {
		// Deliberately unauthenticated: the hub MUST refuse and say so.
		if err := heartbeat(ctx, pairing, ""); err != nil {
			return 0, 0, err
		}
		return 0, 0, errors.New("hub accepted a heartbeat with no token")
	}

	token := cfg.token
	if token == "" {
		token, err = pair(ctx, pairing, cfg.pin)
		if err != nil {
			return 0, 0, err
		}
	}

	if err := heartbeat(ctx, pairing, token); err != nil {
		return 0, 0, err
	}
	if cfg.scenario == scenarioPair {
		return 0, 0, nil
	}

	return openStream(ctx, conn, cfg, token)
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
func openStream(ctx context.Context, conn *grpc.ClientConn, cfg config, token string) (int, int, error) {
	callCtx := metadata.AppendToOutgoingContext(ctx, "authorization", "Bearer "+token)
	stream, err := ecosysv1.NewStreamServiceClient(conn).OpenStream(callCtx)
	if err != nil {
		return 0, 0, mapStatus(err)
	}

	sent := 0
	for i := 0; i < cfg.frames; i++ {
		if cfg.hasAudio() {
			frame := &ecosysv1.StreamFrame{
				Payload: &ecosysv1.StreamFrame_AudioPcm16_16K{AudioPcm16_16K: SyntheticAudioFrame(i)},
			}
			if err := stream.Send(frame); err != nil {
				return sent, 0, mapStatus(err)
			}
			sent++
		}
		if cfg.hasVideo() {
			frame := &ecosysv1.StreamFrame{
				Payload: &ecosysv1.StreamFrame_VideoH264Nal{VideoH264Nal: SyntheticVideoNAL(i)},
			}
			if err := stream.Send(frame); err != nil {
				return sent, 0, mapStatus(err)
			}
			sent++
		}
	}

	if err := stream.CloseSend(); err != nil {
		return sent, 0, mapStatus(err)
	}

	transcripts := 0
	for {
		resp, err := stream.Recv()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return sent, transcripts, mapStatus(err)
		}
		if resp.GetTranscript() != "" {
			transcripts++
		}
	}
	return sent, transcripts, nil
}

// mapStatus converts a gRPC transport failure into either the
// errUnauthenticated sentinel (the expected refusal) or a descriptive error.
func mapStatus(err error) error {
	if status.Code(err) == codes.Unauthenticated {
		return fmt.Errorf("%w: %s", errUnauthenticated, status.Convert(err).Message())
	}
	return err
}
