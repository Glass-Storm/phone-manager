package main

import (
	"io"
	"testing"
	"time"

	ecosysv1 "phone-manager/tools/mockpeer/gen/ecosys/v1"
)

func TestParseFlagsDefaultsToADaemonPeer(t *testing.T) {
	cfg, err := parseFlags(nil, io.Discard)
	if err != nil {
		t.Fatalf("parseFlags returned %v", err)
	}
	if cfg.role != roleDaemon {
		t.Fatalf("role = %q, want %q", cfg.role, roleDaemon)
	}
	if cfg.deviceRole() != ecosysv1.DeviceRole_DEVICE_ROLE_DAEMON {
		t.Fatalf("deviceRole = %v, want DEVICE_ROLE_DAEMON", cfg.deviceRole())
	}
	if cfg.deviceID != mockPeerDeviceID {
		t.Fatalf("deviceID = %q, want %q", cfg.deviceID, mockPeerDeviceID)
	}
	if cfg.imageWidth != defaultImageWidth || cfg.imageHeight != defaultImageHeight {
		t.Fatalf("default image size = %dx%d, want %dx%d",
			cfg.imageWidth, cfg.imageHeight, defaultImageWidth, defaultImageHeight)
	}
	if cfg.imagePath != "" || cfg.audioFile != "" {
		t.Fatalf("preset media must default to unset, got image=%q audio=%q", cfg.imagePath, cfg.audioFile)
	}
	if cfg.ptsMs != 0 {
		t.Fatalf("ptsMs = %d, want 0", cfg.ptsMs)
	}
	if cfg.halfClose {
		t.Fatal("halfClose = true, want false by default")
	}
	if cfg.drainGrace != defaultDrainGrace {
		t.Fatalf("drainGrace = %s, want %s", cfg.drainGrace, defaultDrainGrace)
	}
	if len(cfg.expectations) != 0 {
		t.Fatalf("expectations = %v, want none by default", cfg.expectations)
	}
}

func TestParseFlagsMapsGlassRoleAndPresetMedia(t *testing.T) {
	cfg, err := parseFlags([]string{
		"--role", "glass",
		"--device-id", "glasses-1",
		"--image", "still.jpg",
		"--image-width", "640",
		"--image-height", "480",
		"--pts", "1250",
		"--audio-file", "speech.wav",
	}, io.Discard)
	if err != nil {
		t.Fatalf("parseFlags returned %v", err)
	}
	if cfg.deviceRole() != ecosysv1.DeviceRole_DEVICE_ROLE_GLASS {
		t.Fatalf("deviceRole = %v, want DEVICE_ROLE_GLASS", cfg.deviceRole())
	}
	if cfg.deviceID != "glasses-1" {
		t.Fatalf("deviceID = %q, want glasses-1", cfg.deviceID)
	}
	if cfg.imagePath != "still.jpg" || cfg.audioFile != "speech.wav" {
		t.Fatalf("preset media = image %q audio %q", cfg.imagePath, cfg.audioFile)
	}
	if cfg.imageWidth != 640 || cfg.imageHeight != 480 {
		t.Fatalf("image size = %dx%d, want 640x480", cfg.imageWidth, cfg.imageHeight)
	}
	if cfg.ptsMs != 1250 {
		t.Fatalf("ptsMs = %d, want 1250", cfg.ptsMs)
	}
	if cfg.frames != 0 {
		t.Fatalf("frames = %d, want 0 when preset media is requested without --frames", cfg.frames)
	}
}

func TestParseFlagsKeepsSyntheticFramesWhenExplicitlyRequested(t *testing.T) {
	cfg, err := parseFlags([]string{"--image", "still.jpg", "--frames", "3"}, io.Discard)
	if err != nil {
		t.Fatalf("parseFlags returned %v", err)
	}
	if cfg.frames != 3 {
		t.Fatalf("frames = %d, want the explicit 3", cfg.frames)
	}
}

func TestParseFlagsAcceptsHalfCloseAndExpectGate(t *testing.T) {
	cfg, err := parseFlags([]string{
		"--half-close",
		"--drain-grace", "250ms",
		"--expect", "gesture=1,tts=1,result=1,transcript-min=1",
	}, io.Discard)
	if err != nil {
		t.Fatalf("parseFlags returned %v", err)
	}
	if !cfg.halfClose {
		t.Fatal("halfClose = false, want true")
	}
	if cfg.drainGrace != 250*time.Millisecond {
		t.Fatalf("drainGrace = %s, want 250ms", cfg.drainGrace)
	}
	if len(cfg.expectations) != 4 {
		t.Fatalf("expectations = %v, want 4 assertions", cfg.expectations)
	}
}

func TestParseFlagsRejectsInvalidPeerSettings(t *testing.T) {
	tests := []struct {
		name string
		args []string
	}{
		{name: "unknown role", args: []string{"--role", "hub"}},
		{name: "empty device id", args: []string{"--device-id", ""}},
		{name: "zero image width", args: []string{"--image-width", "0"}},
		{name: "negative image height", args: []string{"--image-height", "-1"}},
		{name: "negative pts", args: []string{"--pts", "-1"}},
		{name: "zero drain grace", args: []string{"--drain-grace", "0s"}},
		{name: "negative drain grace", args: []string{"--drain-grace", "-1s"}},
		{name: "invalid expect", args: []string{"--expect", "gesture=one"}},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := parseFlags(test.args, io.Discard); err == nil {
				t.Fatalf("parseFlags accepted %v", test.args)
			}
		})
	}
}
