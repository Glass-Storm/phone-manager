// Command mockpeer is the Go reference peer for the ecosys.v1 hub contract.
//
// It exists so the transport spike (T6) and the full E2E (T18) can drive the
// REAL gRPC server over a REAL socket without an emulator or a device: it opens
// the bidirectional relay UNAUTHENTICATED, sends a `stream_open` bootstrap frame
// to bind its device id/role, and pushes synthetic media.
//
// Pairing, PIN and bearer tokens were removed from the hub runtime, so this peer
// no longer pairs, heartbeats, or carries an `authorization` header.
//
// Output is a machine-checkable contract, consumed by automation:
//
//	success      -> stdout "session-ok frames=<N> transcripts=<M>", exit 0
//	gate failure -> stdout "e2e-expect-fail", exit 2
//	anything else-> stdout "error: <detail>", exit 1
//
// Preset media can replace (or accompany) the synthetic frames:
//
//	--image <path>       send ONE JPEG still as an `image` frame
//	--audio-file <path>  stream a 16 kHz mono PCM16 WAV as `audio_pcm16_16k`
//
// Inbound hub output (gesture/tts_audio/result) is printed as it arrives, and a
// final `e2e ...` summary line reports the counters an E2E script asserts on.
//
// On its own that summary is vacuous: a run that collected nothing still exits
// 0. Pass --expect (e.g. `--expect gesture=1,tts=1,result=1,transcript-min=1`)
// to turn the counters into a real gate; the run then prints `e2e-expect-ok` or
// `e2e-expect-fail` and exits non-zero on a shortfall.
//
// Every call runs under a context deadline so a hung server can never hang CI.
package main

import (
	"context"
	"flag"
	"fmt"
	"io"
	"os"
	"time"
)

// exit codes: 0 ok, 1 unexpected error, 2 --expect gate failure.
const (
	exitOk             = 0
	exitError          = 1
	exitExpectFailure  = 2
	defaultAddr        = "127.0.0.1:50051"
	defaultFrames      = 10
	defaultTimeout     = 30
	defaultImageWidth  = 320
	defaultImageHeight = 240
	defaultDrainGrace  = 5 * time.Second
)

// config is the parsed command line. Every field is set once, before any RPC.
type config struct {
	addr         string
	frames       int
	timeout      time.Duration
	mode         string
	scenario     string
	role         string
	deviceID     string
	imagePath    string
	imageWidth   int
	imageHeight  int
	ptsMs        int64
	audioFile    string
	halfClose    bool
	drainGrace   time.Duration
	expectations expectations
}

// main is a thin wrapper so the whole CLI (including its exit code) is testable
// through run() without spawning a child process.
func main() {
	os.Exit(run(os.Args[1:], os.Stdout, os.Stderr))
}

// run parses the CLI, drives the scenario, prints the frozen stdout contract,
// and returns the process exit code.
func run(args []string, stdout, stderr io.Writer) int {
	cfg, err := parseFlags(args, stderr)
	if err != nil {
		fmt.Fprintf(stderr, "error: %v\n", err)
		return exitError
	}

	ctx, cancel := context.WithTimeout(context.Background(), cfg.timeout)
	defer cancel()

	result, err := runScenario(ctx, cfg)
	if err != nil {
		fmt.Fprintf(stdout, "error: %v\n", err)
		return exitError
	}

	fmt.Fprintln(stdout, "stream-open-ok")
	fmt.Fprintf(stdout, "session-ok frames=%d transcripts=%d\n", result.frames, result.transcripts)
	fmt.Fprintf(
		stdout,
		"e2e sent_image=%d sent_audio_bytes=%d gestures=%d tts=%d results=%d\n",
		result.sentImage,
		result.sentAudioBytes,
		result.gestures,
		result.tts,
		result.results,
	)

	if len(cfg.expectations) > 0 {
		if err := cfg.expectations.check(result); err != nil {
			fmt.Fprintf(stderr, "e2e-expect-fail: %v\n", err)
			fmt.Fprintln(stdout, "e2e-expect-fail")
			return exitExpectFailure
		}
		fmt.Fprintln(stdout, "e2e-expect-ok")
	}
	return exitOk
}

// parseFlags reads and validates the CLI surface. An invalid combination is a
// usage error: it exits 1 with a message on stderr.
func parseFlags(args []string, stderr io.Writer) (config, error) {
	fs := flag.NewFlagSet("mockpeer", flag.ContinueOnError)
	fs.SetOutput(stderr)
	var cfg config
	var timeoutSeconds int
	var expectSpec string
	fs.StringVar(&cfg.addr, "addr", defaultAddr, "hub host:port to dial (plaintext)")
	fs.IntVar(&cfg.frames, "frames", defaultFrames, "synthetic media frames to send per enabled medium")
	fs.IntVar(&timeoutSeconds, "timeout", defaultTimeout, "overall deadline in seconds")
	fs.StringVar(&cfg.mode, "mode", modeBoth, "media to send: audio|video|both")
	fs.StringVar(&cfg.scenario, "scenario", scenarioFull, "full|stream")
	fs.StringVar(&cfg.role, "role", roleDaemon, "peer role claimed in stream_open: glass|daemon")
	fs.StringVar(&cfg.deviceID, "device-id", mockPeerDeviceID, "device id claimed in stream_open")
	fs.StringVar(&cfg.imagePath, "image", "", "JPEG file sent as ONE image frame (empty: none)")
	fs.IntVar(&cfg.imageWidth, "image-width", defaultImageWidth, "image frame width in pixels")
	fs.IntVar(&cfg.imageHeight, "image-height", defaultImageHeight, "image frame height in pixels")
	fs.Int64Var(&cfg.ptsMs, "pts", 0, "presentation timestamp (ms) for the --image frame")
	fs.StringVar(
		&cfg.audioFile,
		"audio-file",
		"",
		"16 kHz mono PCM16 WAV streamed as audio_pcm16_16k frames (empty: none)",
	)
	fs.BoolVar(
		&cfg.halfClose,
		"half-close",
		false,
		"half-close the send side after sending, then drain replies for --drain-grace",
	)
	fs.DurationVar(
		&cfg.drainGrace,
		"drain-grace",
		defaultDrainGrace,
		"how long to keep reading after --half-close (e.g. 5s, 250ms)",
	)
	fs.StringVar(
		&expectSpec,
		"expect",
		"",
		"comma-separated counter gate, e.g. gesture=1,tts=1,result=1,transcript-min=1",
	)

	if err := fs.Parse(args); err != nil {
		return config{}, err
	}
	cfg.timeout = time.Duration(timeoutSeconds) * time.Second

	if cfg.frames < 0 {
		return config{}, fmt.Errorf("--frames must not be negative")
	}
	if timeoutSeconds <= 0 {
		return config{}, fmt.Errorf("--timeout must be positive")
	}
	switch cfg.role {
	case roleGlass, roleDaemon:
	default:
		return config{}, fmt.Errorf("--role must be glass|daemon, got %q", cfg.role)
	}
	if cfg.deviceID == "" {
		return config{}, fmt.Errorf("--device-id must not be empty")
	}
	if cfg.imageWidth <= 0 || cfg.imageHeight <= 0 {
		return config{}, fmt.Errorf("--image-width/--image-height must be positive")
	}
	if cfg.ptsMs < 0 {
		return config{}, fmt.Errorf("--pts must not be negative, got %d", cfg.ptsMs)
	}
	if cfg.drainGrace <= 0 {
		return config{}, fmt.Errorf("--drain-grace must be positive, got %s", cfg.drainGrace)
	}
	if !flagWasSet(fs, "frames") && (cfg.imagePath != "" || cfg.audioFile != "") {
		cfg.frames = 0
	}
	switch cfg.mode {
	case modeAudio, modeVideo, modeBoth:
	default:
		return config{}, fmt.Errorf("--mode must be audio|video|both, got %q", cfg.mode)
	}
	switch cfg.scenario {
	case scenarioFull, scenarioStream:
	default:
		return config{}, fmt.Errorf("--scenario must be full|stream, got %q", cfg.scenario)
	}

	expectations, err := parseExpectations(expectSpec)
	if err != nil {
		return config{}, err
	}
	cfg.expectations = expectations
	return cfg, nil
}

func flagWasSet(fs *flag.FlagSet, name string) bool {
	set := false
	fs.Visit(func(visited *flag.Flag) {
		if visited.Name == name {
			set = true
		}
	})
	return set
}

// hasAudio / hasVideo let the scenario read intent without repeating the switch.
func (c config) hasAudio() bool { return c.mode == modeAudio || c.mode == modeBoth }
func (c config) hasVideo() bool { return c.mode == modeVideo || c.mode == modeBoth }
