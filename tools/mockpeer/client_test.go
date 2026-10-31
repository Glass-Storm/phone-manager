package main

import (
	"bytes"
	"context"
	"errors"
	"io"
	"net"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	ecosysv1 "phone-manager/tools/mockpeer/gen/ecosys/v1"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/test/bufconn"
)

type fakeHubMode int

const (
	// hubDrainThenReply reads every client frame until the client half-closes,
	// THEN sends the scripted replies. This is the late-TTS shape.
	hubDrainThenReply fakeHubMode = iota
	// hubHold reads until the client's context ends, never ending on its own.
	hubHold
)

// fakeHub is an in-process ecosys.v1 StreamService used by the client tests.
type fakeHub struct {
	ecosysv1.UnimplementedStreamServiceServer

	mode     fakeHubMode
	outbound []*ecosysv1.StreamFrame

	mu       sync.Mutex
	open     *ecosysv1.StreamOpenRequest
	received []*ecosysv1.StreamFrame
}

func (h *fakeHub) OpenStream(stream ecosysv1.StreamService_OpenStreamServer) error {
	first, err := stream.Recv()
	if err != nil {
		return err
	}
	h.mu.Lock()
	h.open = first.GetStreamOpen()
	h.mu.Unlock()

	if h.mode == hubHold {
		for {
			frame, err := stream.Recv()
			if errors.Is(err, io.EOF) {
				<-stream.Context().Done()
				return nil
			}
			if err != nil {
				return nil
			}
			h.record(frame)
		}
	}

	for {
		frame, err := stream.Recv()
		if errors.Is(err, io.EOF) {
			break
		}
		if err != nil {
			return err
		}
		h.record(frame)
	}
	for _, frame := range h.outbound {
		if err := stream.Send(frame); err != nil {
			return err
		}
	}
	return nil
}

func (h *fakeHub) record(frame *ecosysv1.StreamFrame) {
	h.mu.Lock()
	h.received = append(h.received, frame)
	h.mu.Unlock()
}

func (h *fakeHub) openRequest() *ecosysv1.StreamOpenRequest {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.open
}

func (h *fakeHub) receivedFrames() []*ecosysv1.StreamFrame {
	h.mu.Lock()
	defer h.mu.Unlock()
	return append([]*ecosysv1.StreamFrame(nil), h.received...)
}

func startBufconnHub(t *testing.T, hub ecosysv1.StreamServiceServer) *grpc.ClientConn {
	t.Helper()
	listener := bufconn.Listen(1 << 20)
	server := grpc.NewServer()
	ecosysv1.RegisterStreamServiceServer(server, hub)
	go func() { _ = server.Serve(listener) }()
	t.Cleanup(server.Stop)

	conn, err := grpc.NewClient(
		"passthrough:///bufnet",
		grpc.WithContextDialer(func(ctx context.Context, _ string) (net.Conn, error) {
			return listener.DialContext(ctx)
		}),
		grpc.WithTransportCredentials(insecure.NewCredentials()),
	)
	if err != nil {
		t.Fatalf("dial in-process hub: %v", err)
	}
	t.Cleanup(func() { _ = conn.Close() })
	return conn
}

func startTCPHub(t *testing.T, hub ecosysv1.StreamServiceServer) string {
	t.Helper()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	server := grpc.NewServer()
	ecosysv1.RegisterStreamServiceServer(server, hub)
	go func() { _ = server.Serve(listener) }()
	t.Cleanup(server.Stop)
	return listener.Addr().String()
}

func captureStdout(t *testing.T, action func()) string {
	t.Helper()
	reader, writer, err := os.Pipe()
	if err != nil {
		t.Fatalf("pipe: %v", err)
	}
	original := os.Stdout
	os.Stdout = writer
	defer func() { os.Stdout = original }()
	action()
	_ = writer.Close()
	data, _ := io.ReadAll(reader)
	return string(data)
}

func gestureFrame() *ecosysv1.StreamFrame {
	return &ecosysv1.StreamFrame{
		Payload: &ecosysv1.StreamFrame_Gesture{
			Gesture: &ecosysv1.GestureResult{Text: "hello", Label: "WAVE", Confidence: 0.9},
		},
	}
}

func ttsFrame() *ecosysv1.StreamFrame {
	return &ecosysv1.StreamFrame{
		Payload: &ecosysv1.StreamFrame_TtsAudio{
			TtsAudio: &ecosysv1.TtsAudio{Pcm16_16K: []byte{1, 2, 3, 4}},
		},
	}
}

func transcriptFrame(text string) *ecosysv1.StreamFrame {
	return &ecosysv1.StreamFrame{Payload: &ecosysv1.StreamFrame_Transcript{Transcript: text}}
}

func resultFrame(text string) *ecosysv1.StreamFrame {
	return &ecosysv1.StreamFrame{
		Payload: &ecosysv1.StreamFrame_Result{
			Result: &ecosysv1.StreamResult{Text: text, SpeakerLabel: "alice"},
		},
	}
}

func TestOpenStreamBindsRoleAndSendsPresetMedia(t *testing.T) {
	jpeg := []byte{0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10, 'J', 'F', 'I', 'F', 0x00, 0xFF, 0xD9}
	pcm := make([]byte, 700)
	for i := range pcm {
		pcm[i] = byte(i % 251)
	}
	wav := buildTestWAV(
		wavTestChunk{id: "fmt ", body: fmtChunkBody(wavFormatPCM, 1, audioSampleRateHz, 16)},
		wavTestChunk{id: "data", body: pcm},
	)
	jpegPath := writeTempMedia(t, "still.jpg", jpeg)
	wavPath := writeTempMedia(t, "speech.wav", wav)
	wantAudioFrames := len(chunkPCM16(pcm))

	tests := []struct {
		role     string
		wantRole ecosysv1.DeviceRole
	}{
		{role: roleGlass, wantRole: ecosysv1.DeviceRole_DEVICE_ROLE_GLASS},
		{role: roleDaemon, wantRole: ecosysv1.DeviceRole_DEVICE_ROLE_DAEMON},
	}

	for _, test := range tests {
		t.Run(test.role, func(t *testing.T) {
			hub := &fakeHub{
				mode: hubDrainThenReply,
				outbound: []*ecosysv1.StreamFrame{
					gestureFrame(), ttsFrame(), transcriptFrame("hello"), resultFrame("world"),
				},
			}
			conn := startBufconnHub(t, hub)
			cfg := config{
				role:        test.role,
				deviceID:    "peer-1",
				frames:      0,
				mode:        modeBoth,
				imagePath:   jpegPath,
				imageWidth:  320,
				imageHeight: 240,
				ptsMs:       1250,
				audioFile:   wavPath,
				halfClose:   true,
				drainGrace:  2 * time.Second,
			}

			ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
			defer cancel()

			var result scenarioResult
			var err error
			output := captureStdout(t, func() {
				result, err = openStream(ctx, conn, cfg)
			})
			if err != nil {
				t.Fatalf("openStream returned %v", err)
			}

			if result.inboundEnd != inboundEndEOF {
				t.Fatalf("inboundEnd = %q, want %q", result.inboundEnd, inboundEndEOF)
			}
			if result.sentImage != 1 {
				t.Fatalf("sentImage = %d, want 1", result.sentImage)
			}
			if result.sentAudioBytes != len(pcm) {
				t.Fatalf("sentAudioBytes = %d, want %d", result.sentAudioBytes, len(pcm))
			}
			if want := 1 + wantAudioFrames; result.frames != want {
				t.Fatalf("frames = %d, want %d", result.frames, want)
			}
			if result.gestures != 1 || result.tts != 1 || result.results != 1 {
				t.Fatalf("inbound counters = g%d t%d r%d, want 1/1/1",
					result.gestures, result.tts, result.results)
			}
			if result.transcripts != 2 {
				t.Fatalf("transcripts = %d, want 2", result.transcripts)
			}

			open := hub.openRequest()
			if open.GetDeviceId() != "peer-1" {
				t.Fatalf("bound device id = %q, want peer-1", open.GetDeviceId())
			}
			if open.GetRole() != test.wantRole {
				t.Fatalf("bound role = %v, want %v", open.GetRole(), test.wantRole)
			}

			received := hub.receivedFrames()
			if len(received) != result.frames {
				t.Fatalf("hub received %d frames, want %d", len(received), result.frames)
			}
			image := received[0].GetImage()
			if image == nil {
				t.Fatalf("first media frame = %T, want image", received[0].GetPayload())
			}
			if image.GetPtsMs() != 1250 {
				t.Fatalf("image pts = %d, want 1250", image.GetPtsMs())
			}
			if image.GetWidth() != 320 || image.GetHeight() != 240 {
				t.Fatalf("image size = %dx%d, want 320x240", image.GetWidth(), image.GetHeight())
			}
			if !bytes.Equal(image.GetJpeg(), jpeg) {
				t.Fatal("image payload changed in flight")
			}

			var audio []byte
			for _, frame := range received[1:] {
				audio = append(audio, frame.GetAudioPcm16_16K()...)
			}
			if !bytes.Equal(audio, pcm) {
				t.Fatalf("hub received %d audio bytes, want %d", len(audio), len(pcm))
			}

			for _, line := range []string{"gesture text=hello", "tts bytes=4", "result text=world speaker=alice"} {
				if !strings.Contains(output, line) {
					t.Fatalf("stdout missing %q:\n%s", line, output)
				}
			}
		})
	}
}

func TestOpenStreamHalfCloseDrainsLateReplies(t *testing.T) {
	hub := &fakeHub{mode: hubDrainThenReply, outbound: []*ecosysv1.StreamFrame{ttsFrame()}}
	conn := startBufconnHub(t, hub)
	cfg := config{
		role: roleDaemon, deviceID: "peer-1", frames: 0, mode: modeBoth,
		halfClose: true, drainGrace: time.Second,
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	result, err := openStream(ctx, conn, cfg)
	if err != nil {
		t.Fatalf("openStream returned %v", err)
	}
	if result.tts != 1 {
		t.Fatalf("tts = %d, want the reply sent after the client stopped sending", result.tts)
	}
	if result.inboundEnd != inboundEndEOF {
		t.Fatalf("inboundEnd = %q, want %q", result.inboundEnd, inboundEndEOF)
	}
}

func TestOpenStreamEndsOnEOFOrDeadline(t *testing.T) {
	t.Run("server EOF", func(t *testing.T) {
		hub := &fakeHub{mode: hubDrainThenReply}
		conn := startBufconnHub(t, hub)
		cfg := config{role: roleDaemon, deviceID: "peer-1", mode: modeBoth, halfClose: true, drainGrace: time.Second}

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()

		result, err := openStream(ctx, conn, cfg)
		if err != nil {
			t.Fatalf("openStream returned %v", err)
		}
		if result.inboundEnd != inboundEndEOF {
			t.Fatalf("inboundEnd = %q, want %q", result.inboundEnd, inboundEndEOF)
		}
	})

	t.Run("client deadline", func(t *testing.T) {
		hub := &fakeHub{mode: hubHold}
		conn := startBufconnHub(t, hub)
		cfg := config{role: roleDaemon, deviceID: "peer-1", mode: modeBoth}

		ctx, cancel := context.WithTimeout(context.Background(), 250*time.Millisecond)
		defer cancel()

		result, err := openStream(ctx, conn, cfg)
		if err != nil {
			t.Fatalf("openStream returned %v", err)
		}
		if result.inboundEnd != inboundEndDeadline {
			t.Fatalf("inboundEnd = %q, want %q", result.inboundEnd, inboundEndDeadline)
		}
	})
}

func TestRunExpectGateControlsTheExitCode(t *testing.T) {
	hub := &fakeHub{
		mode:     hubDrainThenReply,
		outbound: []*ecosysv1.StreamFrame{gestureFrame(), ttsFrame(), resultFrame("world")},
	}
	addr := startTCPHub(t, hub)
	base := []string{
		"--addr", addr,
		"--role", "glass",
		"--device-id", "peer-1",
		"--frames", "0",
		"--half-close",
		"--drain-grace", "1s",
		"--timeout", "5",
	}

	var stdout, stderr bytes.Buffer
	code := run(append(append([]string{}, base...), "--expect", "gesture=1,tts=1,result=1,transcript-min=1"), &stdout, &stderr)
	if code != exitOk {
		t.Fatalf("satisfied gate exit code = %d, want %d (stderr %s)", code, exitOk, stderr.String())
	}
	if !strings.Contains(stdout.String(), "session-ok") {
		t.Fatalf("stdout missing session-ok:\n%s", stdout.String())
	}
	if !strings.Contains(stdout.String(), "e2e-expect-ok") {
		t.Fatalf("stdout missing e2e-expect-ok:\n%s", stdout.String())
	}

	stdout.Reset()
	stderr.Reset()
	code = run(append(append([]string{}, base...), "--expect", "gesture=2"), &stdout, &stderr)
	if code != exitExpectFailure {
		t.Fatalf("failed gate exit code = %d, want %d", code, exitExpectFailure)
	}
	if !strings.Contains(stdout.String(), "e2e-expect-fail") {
		t.Fatalf("stdout missing e2e-expect-fail:\n%s", stdout.String())
	}
	if strings.Contains(stdout.String(), "e2e-expect-ok") {
		t.Fatalf("failed gate printed e2e-expect-ok:\n%s", stdout.String())
	}
}
