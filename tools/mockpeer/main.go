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
//	anything else-> stdout "error: <detail>", exit 1
//
// Every call runs under a context deadline so a hung server can never hang CI.
package main

import (
	"context"
	"flag"
	"fmt"
	"os"
	"time"
)

// exit codes: 0 ok, 1 unexpected error.
const (
	exitOk         = 0
	exitError      = 1
	defaultAddr    = "127.0.0.1:50051"
	defaultFrames  = 10
	defaultTimeout = 30
)

// config is the parsed command line. Every field is set once, before any RPC.
type config struct {
	addr     string
	frames   int
	timeout  time.Duration
	mode     string
	scenario string
}

func main() {
	cfg, err := parseFlags(os.Args[1:])
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(exitError)
	}

	ctx, cancel := context.WithTimeout(context.Background(), cfg.timeout)
	defer cancel()

	result, err := runScenario(ctx, cfg)
	if err != nil {
		fmt.Printf("error: %v\n", err)
		os.Exit(exitError)
	}
	fmt.Println("stream-open-ok")
	fmt.Printf("session-ok frames=%d transcripts=%d\n", result.frames, result.transcripts)
	os.Exit(exitOk)
}

// parseFlags reads and validates the CLI surface. An invalid combination is a
// usage error: it exits 1 with a message on stderr.
func parseFlags(args []string) (config, error) {
	fs := flag.NewFlagSet("mockpeer", flag.ContinueOnError)
	var cfg config
	var timeoutSeconds int
	fs.StringVar(&cfg.addr, "addr", defaultAddr, "hub host:port to dial (plaintext)")
	fs.IntVar(&cfg.frames, "frames", defaultFrames, "synthetic media frames to send per enabled medium")
	fs.IntVar(&timeoutSeconds, "timeout", defaultTimeout, "overall deadline in seconds")
	fs.StringVar(&cfg.mode, "mode", modeBoth, "media to send: audio|video|both")
	fs.StringVar(&cfg.scenario, "scenario", scenarioFull, "full|stream")

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
	return cfg, nil
}

// hasAudio / hasVideo let the scenario read intent without repeating the switch.
func (c config) hasAudio() bool { return c.mode == modeAudio || c.mode == modeBoth }
func (c config) hasVideo() bool { return c.mode == modeVideo || c.mode == modeBoth }
