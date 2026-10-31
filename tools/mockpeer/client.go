package main

import (
	"context"
	"errors"
	"fmt"
	"io"
	"time"

	ecosysv1 "phone-manager/tools/mockpeer/gen/ecosys/v1"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
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

// The roles a peer can claim on the stream bootstrap.
const (
	roleGlass  = "glass"
	roleDaemon = "daemon"
)

// The synthetic device id/role this peer claims on the stream bootstrap.
const mockPeerDeviceID = "mockpeer"

// How the receive loop ended. An EOF is the server closing the stream; a
// deadline is this peer's own --timeout; drain-grace is the bounded read window
// that --half-close opens after the send side is closed.
const (
	inboundEndEOF        = "eof"
	inboundEndDeadline   = "deadline"
	inboundEndDrainGrace = "drain-grace"
)

// scenarioResult reports what a run accomplished: media frames SENT and the
// inbound hub output RECEIVED.
type scenarioResult struct {
	frames         int
	transcripts    int
	sentImage      int
	sentAudioBytes int
	gestures       int
	tts            int
	results        int
	inboundEnd     string
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

// openStream sends the `stream_open` bootstrap frame, pushes synthetic and/or
// preset media, then drains result frames and counts/prints what the hub sent
// back. No `authorization` metadata is ever attached.
func openStream(ctx context.Context, conn *grpc.ClientConn, cfg config) (scenarioResult, error) {
	streamCtx, streamCancel := context.WithCancel(ctx)
	defer streamCancel()

	stream, err := ecosysv1.NewStreamServiceClient(conn).OpenStream(streamCtx)
	if err != nil {
		return scenarioResult{}, err
	}

	result := scenarioResult{}

	// With bearer-token auth removed this is the ONLY thing that tells the hub who
	// is on the other end and how to route frames.
	if err := stream.Send(streamOpenFrame(cfg.deviceID, cfg.deviceRole())); err != nil {
		return result, err
	}

	for i := 0; i < cfg.frames; i++ {
		if cfg.hasAudio() {
			frame := &ecosysv1.StreamFrame{
				Payload: &ecosysv1.StreamFrame_AudioPcm16_16K{AudioPcm16_16K: SyntheticAudioFrame(i)},
			}
			if err := stream.Send(frame); err != nil {
				return result, err
			}
			result.frames++
		}
		if cfg.hasVideo() {
			frame := &ecosysv1.StreamFrame{
				Payload: &ecosysv1.StreamFrame_VideoH264Nal{VideoH264Nal: SyntheticVideoNAL(i)},
			}
			if err := stream.Send(frame); err != nil {
				return result, err
			}
			result.frames++
		}
	}

	if cfg.imagePath != "" {
		image, err := loadImageFrame(cfg.imagePath, cfg.ptsMs, int32(cfg.imageWidth), int32(cfg.imageHeight))
		if err != nil {
			return result, fmt.Errorf("image %s: %w", cfg.imagePath, err)
		}
		if err := stream.Send(&ecosysv1.StreamFrame{
			Payload: &ecosysv1.StreamFrame_Image{Image: image},
		}); err != nil {
			return result, err
		}
		result.frames++
		result.sentImage = 1
	}

	if cfg.audioFile != "" {
		frames, bytesSent, err := streamWAVAudio(ctx, stream, cfg.audioFile)
		result.frames += frames
		result.sentAudioBytes = bytesSent
		if err != nil {
			return result, fmt.Errorf("audio file %s: %w", cfg.audioFile, err)
		}
	}

	if cfg.halfClose {
		return drainAfterHalfClose(stream, streamCtx, ctx, cfg.drainGrace, streamCancel, &result)
	}

	// Default: a real peer keeps the bidirectional stream open after its sends,
	// so hub work that finishes AFTER the sends (e.g. ~2 s TTS synthesis) still
	// has a live session to be delivered on. Stay open and keep reading until
	// the server ends the stream (io.EOF) or the configured deadline expires.
	result.inboundEnd = inboundEndDeadline
	for {
		resp, err := stream.Recv()
		if errors.Is(err, io.EOF) {
			result.inboundEnd = inboundEndEOF
			break
		}
		if err != nil {
			// The deadline elapsing is a normal end for an indefinitely-open
			// stream, not a failure: report what was collected.
			if isStreamDeadline(ctx, err) {
				break
			}
			return result, err
		}
		recordInbound(resp, &result)
	}
	return result, nil
}

// drainAfterHalfClose closes the SEND side, then keeps reading for a bounded
// window so late tts_audio/result frames produced after this peer stopped
// sending are still counted. This mirrors the real Pi's teardown.
func drainAfterHalfClose(
	stream ecosysv1.StreamService_OpenStreamClient,
	streamCtx context.Context,
	parent context.Context,
	grace time.Duration,
	cancelStream context.CancelFunc,
	result *scenarioResult,
) (scenarioResult, error) {
	if err := stream.CloseSend(); err != nil {
		return *result, err
	}

	// The drain window is a TIMER on top of the stream context: when it fires,
	// cancel the stream and treat the resulting cancellation as the normal end
	// of the grace period rather than a transport failure.
	graceTimer := time.AfterFunc(grace, cancelStream)
	defer graceTimer.Stop()

	result.inboundEnd = inboundEndDrainGrace
	for {
		resp, err := stream.Recv()
		if errors.Is(err, io.EOF) {
			result.inboundEnd = inboundEndEOF
			break
		}
		if err != nil {
			if !isStreamDeadline(streamCtx, err) {
				return *result, err
			}
			if parent.Err() != nil {
				result.inboundEnd = inboundEndDeadline
			}
			break
		}
		recordInbound(resp, result)
	}
	return *result, nil
}

// isStreamDeadline reports whether a Recv error is the configured deadline (or a
// parent cancel) rather than a real transport failure.
func isStreamDeadline(ctx context.Context, err error) bool {
	if errors.Is(err, context.DeadlineExceeded) || errors.Is(err, context.Canceled) {
		return true
	}
	if ctx.Err() != nil {
		return true
	}
	code := status.Code(err)
	return code == codes.DeadlineExceeded || code == codes.Canceled
}

func recordInbound(frame *ecosysv1.StreamFrame, result *scenarioResult) {
	if frame.GetTranscript() != "" || frame.GetResult().GetText() != "" {
		result.transcripts++
	}
	switch payload := frame.GetPayload().(type) {
	case *ecosysv1.StreamFrame_Gesture:
		gesture := payload.Gesture
		fmt.Printf(
			"gesture text=%s label=%s confidence=%v\n",
			gesture.GetText(),
			gesture.GetLabel(),
			gesture.GetConfidence(),
		)
		result.gestures++
	case *ecosysv1.StreamFrame_TtsAudio:
		fmt.Printf("tts bytes=%d\n", len(payload.TtsAudio.GetPcm16_16K()))
		result.tts++
	case *ecosysv1.StreamFrame_Result:
		fmt.Printf(
			"result text=%s speaker=%s\n",
			payload.Result.GetText(),
			payload.Result.GetSpeakerLabel(),
		)
		result.results++
	}
}

// deviceRole maps the CLI role onto the frozen wire enum.
func (c config) deviceRole() ecosysv1.DeviceRole {
	if c.role == roleGlass {
		return ecosysv1.DeviceRole_DEVICE_ROLE_GLASS
	}
	return ecosysv1.DeviceRole_DEVICE_ROLE_DAEMON
}

// streamOpenFrame is the session-binding bootstrap sent as the first stream frame.
func streamOpenFrame(deviceID string, role ecosysv1.DeviceRole) *ecosysv1.StreamFrame {
	return &ecosysv1.StreamFrame{
		Payload: &ecosysv1.StreamFrame_StreamOpen{
			StreamOpen: &ecosysv1.StreamOpenRequest{
				DeviceId: deviceID,
				Role:     role,
			},
		},
	}
}
