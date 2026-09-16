// Command mockpeer is the Go reference peer for the ecosys.v1 hub contract.
//
// It exists so the transport spike (T6) and the full E2E (T18) can drive the
// REAL gRPC server over a REAL socket without an emulator or a device: it pairs
// with a PIN, heartbeats with the issued bearer token, and pushes synthetic
// media through the bidirectional relay.
//
// Output is a machine-checkable contract, consumed by automation:
//
//	success      -> stdout "session-ok frames=<N> transcripts=<M>", exit 0
//	auth reject  -> stdout "UNAUTHENTICATED", exit 2
//	pin reject   -> stdout "pair-rejected reason=<reason>", exit 2
//	anything else-> stdout "error: <detail>", exit 1
//
// Every call runs under a context deadline so a hung server can never hang CI.
package main

import (
	"context"
	"errors"
	"flag"
	"fmt"
	"os"
	"time"
)

// exit codes: 0 ok, 1 unexpected error, 2 an expected machine-checkable rejection.
const (
	exitOk         = 0
	exitError      = 1
	exitRejection  = 2
	defaultAddr    = "127.0.0.1:50051"
	defaultFrames  = 10
	defaultTimeout = 30
)

// config is the parsed command line. Every field is set once, before any RPC.
type config struct {
	addr        string
	pin         string
	token       string
	tokenFile   string
	tokenOut    string
	noToken     bool
	expectUnaut bool
	frames      int
	timeout     time.Duration
	mode        string
	scenario    string
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
	switch {
	case err == nil:
		switch cfg.scenario {
		case scenarioPair:
			// T6's original single-line success contract; unchanged.
			fmt.Println("pair-ok heartbeat-ok")
		case scenarioFull:
			// The T18 ordered transcript: pair, then liveness, then the relay.
			fmt.Println("pair-ok")
			fmt.Println("heartbeat-ok")
			fmt.Printf("session-ok frames=%d transcripts=%d\n", result.frames, result.transcripts)
		default:
			fmt.Printf("session-ok frames=%d transcripts=%d\n", result.frames, result.transcripts)
		}
		os.Exit(exitOk)

	case errors.Is(err, errUnauthenticated):
		fmt.Println("UNAUTHENTICATED")
		os.Exit(exitRejection)

	default:
		var rejected *pairRejectedError
		if errors.As(err, &rejected) {
			fmt.Printf("pair-rejected reason=%s\n", rejected.reason)
			os.Exit(exitRejection)
		}
		fmt.Printf("error: %v\n", err)
		os.Exit(exitError)
	}
}

// parseFlags reads and validates the CLI surface. An invalid combination is a
// usage error, not a rejection: it exits 1 with a message on stderr.
func parseFlags(args []string) (config, error) {
	fs := flag.NewFlagSet("mockpeer", flag.ContinueOnError)
	var cfg config
	var timeoutSeconds int
	fs.StringVar(&cfg.addr, "addr", defaultAddr, "hub host:port to dial (plaintext)")
	fs.StringVar(&cfg.pin, "pin", "", "6-digit pairing PIN for the open window")
	fs.StringVar(&cfg.token, "token", "", "bearer token; skips Pair when set")
	fs.StringVar(&cfg.tokenFile, "token-file", "", "read the bearer token from this file instead of pairing")
	fs.StringVar(&cfg.tokenOut, "token-out", "", "write the issued token to this file (0600); keeps it OUT of stdout")
	fs.BoolVar(&cfg.noToken, "no-token", false, "skip Pair and heartbeat WITHOUT metadata (must be rejected)")
	fs.BoolVar(&cfg.expectUnaut, "expect-unauthenticated", false, "heartbeat with the token and REQUIRE an UNAUTHENTICATED refusal (revoked-token leg)")
	fs.IntVar(&cfg.frames, "frames", defaultFrames, "synthetic media frames to send per enabled medium")
	fs.IntVar(&timeoutSeconds, "timeout", defaultTimeout, "overall deadline in seconds")
	fs.StringVar(&cfg.mode, "mode", modeBoth, "media to send: audio|video|both")
	fs.StringVar(&cfg.scenario, "scenario", scenarioFull, "full|pair|stream")

	if err := fs.Parse(args); err != nil {
		return config{}, err
	}
	cfg.timeout = time.Duration(timeoutSeconds) * time.Second

	credentialSources := 0
	if cfg.token != "" {
		credentialSources++
	}
	if cfg.tokenFile != "" {
		credentialSources++
	}
	if cfg.noToken {
		credentialSources++
	}
	if credentialSources > 1 {
		return config{}, errors.New("--token, --token-file and --no-token are mutually exclusive")
	}
	if cfg.expectUnaut && cfg.token == "" && cfg.tokenFile == "" {
		return config{}, errors.New("--expect-unauthenticated requires --token or --token-file")
	}
	if cfg.expectUnaut && cfg.noToken {
		return config{}, errors.New("--expect-unauthenticated is redundant with --no-token")
	}
	if cfg.frames < 0 {
		return config{}, errors.New("--frames must not be negative")
	}
	if timeoutSeconds <= 0 {
		return config{}, errors.New("--timeout must be positive")
	}
	switch cfg.mode {
	case modeAudio, modeVideo, modeBoth:
	default:
		return config{}, fmt.Errorf("--mode must be audio|video|both, got %q", cfg.mode)
	}
	switch cfg.scenario {
	case scenarioFull, scenarioPair, scenarioStream:
	default:
		return config{}, fmt.Errorf("--scenario must be full|pair|stream, got %q", cfg.scenario)
	}
	if cfg.scenario == scenarioStream && cfg.token == "" && cfg.tokenFile == "" && !cfg.noToken {
		return config{}, errors.New("--scenario stream requires --token (or --token-file, or --no-token to prove rejection)")
	}
	return cfg, nil
}

// hasAudio / hasVideo let the scenario read intent without repeating the switch.
func (c config) hasAudio() bool { return c.mode == modeAudio || c.mode == modeBoth }
func (c config) hasVideo() bool { return c.mode == modeVideo || c.mode == modeBoth }
