package main

import (
	"strings"
	"testing"
)

func TestParseExpectationsAcceptsExactAndMinimumGates(t *testing.T) {
	set, err := parseExpectations("gesture=1,tts=1,result=1,transcript-min=1")
	if err != nil {
		t.Fatalf("parseExpectations returned %v", err)
	}
	if len(set) != 4 {
		t.Fatalf("parseExpectations returned %d assertions, want 4", len(set))
	}

	result := scenarioResult{gestures: 1, tts: 1, results: 1, transcripts: 2}
	if err := set.check(result); err != nil {
		t.Fatalf("check returned %v for a satisfying result", err)
	}
}

func TestParseExpectationsReportsEveryShortfall(t *testing.T) {
	set, err := parseExpectations("gesture=1,tts=2,transcript-min=1")
	if err != nil {
		t.Fatalf("parseExpectations returned %v", err)
	}

	err = set.check(scenarioResult{gestures: 0, tts: 1, transcripts: 0})
	if err == nil {
		t.Fatal("check accepted a result below every gate")
	}
	for _, fragment := range []string{
		"gestures expected == 1, got 0",
		"tts expected == 2, got 1",
		"transcripts expected >= 1, got 0",
	} {
		if !strings.Contains(err.Error(), fragment) {
			t.Fatalf("check error = %q, want it to contain %q", err, fragment)
		}
	}
}

func TestParseExpectationsSupportsEveryCounterAlias(t *testing.T) {
	set, err := parseExpectations(
		"frames=1,transcripts=2,sent_image=1,audio-bytes=640,gestures=1,tts=1,results=1",
	)
	if err != nil {
		t.Fatalf("parseExpectations returned %v", err)
	}
	result := scenarioResult{
		frames: 1, transcripts: 2, sentImage: 1, sentAudioBytes: 640,
		gestures: 1, tts: 1, results: 1,
	}
	if err := set.check(result); err != nil {
		t.Fatalf("check returned %v", err)
	}
}

func TestParseExpectationsRejectsInvalidSpecs(t *testing.T) {
	tests := []struct {
		name    string
		spec    string
		wantErr string
	}{
		{name: "empty assertion", spec: "gesture=1,,tts=1", wantErr: "empty assertion"},
		{name: "missing value", spec: "gesture=", wantErr: "key=value"},
		{name: "missing equals", spec: "gesture", wantErr: "key=value"},
		{name: "unknown key", spec: "mystery=1", wantErr: "unknown key"},
		{name: "not an integer", spec: "gesture=one", wantErr: "not an integer"},
		{name: "negative", spec: "gesture=-1", wantErr: "must not be negative"},
		{name: "duplicate exact", spec: "gesture=1,gestures=1", wantErr: "repeats"},
		{name: "duplicate minimum", spec: "tts-min=1,tts-min=2", wantErr: "repeats"},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			if _, err := parseExpectations(test.spec); err == nil {
				t.Fatalf("parseExpectations accepted %q", test.spec)
			} else if !strings.Contains(err.Error(), test.wantErr) {
				t.Fatalf("parseExpectations error = %q, want it to contain %q", err, test.wantErr)
			}
		})
	}
}

func TestParseExpectationsEmptySpecDisablesTheGate(t *testing.T) {
	for _, spec := range []string{"", "   "} {
		set, err := parseExpectations(spec)
		if err != nil {
			t.Fatalf("parseExpectations(%q) returned %v", spec, err)
		}
		if len(set) != 0 {
			t.Fatalf("parseExpectations(%q) returned %d assertions, want 0", spec, len(set))
		}
	}
}
