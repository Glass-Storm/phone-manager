package main

import (
	"context"
	"errors"
	"fmt"
	"io"

	ecosysv1 "phone-manager/tools/mockpeer/gen/ecosys/v1"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// The two scenarios the CLI can run. They behave identically now that auth is
// gone; both are kept so existing automation flags keep resolving.
const (
	scenarioFull   = "full"
	scenarioStream = "stream"
)

// The media selectors.
const (
	modeAudio = "audio"
	modeVideo = "video"
	modeBoth  = "both"
)

// The synthetic device id/role this peer claims on the stream bootstrap.
const mockPeerDeviceID = "mockpeer"

// scenarioResult reports what a run accomplished: media frames SENT and
// transcript frames RECEIVED.
type scenarioResult struct {
	frames      int
	transcripts int
}

// runScenario dials the hub and drives the unauthenticated relay.
//
// Plaintext only: the LAN hub is cleartext by design and TLS on Android is the
// known-broken path, so no credentials beyond insecure are configured.
func runScenario(ctx context.Context, cfg config) (scenarioResult, error) {
	conn, err := grpc.NewClient(cfg.addr, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return scenarioResult{}, fmt.Errorf("dial %s: %w", cfg.addr, err)
	}
	defer conn.Close()

	return openStream(ctx, conn, cfg)
}

// openStream sends the `stream_open` bootstrap frame, pushes synthetic media and
// drains result frames until the hub closes the stream, counting the transcripts
// it produced. No `authorization` metadata is ever attached.
func openStream(ctx context.Context, conn *grpc.ClientConn, cfg config) (scenarioResult, error) {
	stream, err := ecosysv1.NewStreamServiceClient(conn).OpenStream(ctx)
	if err != nil {
		return scenarioResult{}, err
	}

	// With bearer-token auth removed this is the ONLY thing that tells the hub who
	// is on the other end and how to route frames.
	if err := stream.Send(streamOpenFrame()); err != nil {
		return scenarioResult{}, err
	}

	sent := 0
	for i := 0; i < cfg.frames; i++ {
		if cfg.hasAudio() {
			frame := &ecosysv1.StreamFrame{
				Payload: &ecosysv1.StreamFrame_AudioPcm16_16K{AudioPcm16_16K: SyntheticAudioFrame(i)},
			}
			if err := stream.Send(frame); err != nil {
				return scenarioResult{frames: sent}, err
			}
			sent++
		}
		if cfg.hasVideo() {
			frame := &ecosysv1.StreamFrame{
				Payload: &ecosysv1.StreamFrame_VideoH264Nal{VideoH264Nal: SyntheticVideoNAL(i)},
			}
			if err := stream.Send(frame); err != nil {
				return scenarioResult{frames: sent}, err
			}
			sent++
		}
	}

	if err := stream.CloseSend(); err != nil {
		return scenarioResult{frames: sent}, err
	}

	transcripts := 0
	for {
		resp, err := stream.Recv()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return scenarioResult{frames: sent, transcripts: transcripts}, err
		}
		if resp.GetTranscript() != "" || resp.GetResult().GetText() != "" {
			transcripts++
		}
	}
	return scenarioResult{frames: sent, transcripts: transcripts}, nil
}

// streamOpenFrame is the session-binding bootstrap sent as the first stream frame.
func streamOpenFrame() *ecosysv1.StreamFrame {
	return &ecosysv1.StreamFrame{
		Payload: &ecosysv1.StreamFrame_StreamOpen{
			StreamOpen: &ecosysv1.StreamOpenRequest{
				DeviceId: mockPeerDeviceID,
				Role:     ecosysv1.DeviceRole_DEVICE_ROLE_GLASS,
			},
		},
	}
}
