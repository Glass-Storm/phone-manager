package main

import (
	"fmt"
	"strconv"
	"strings"
)

// metricKind identifies one counter an --expect assertion can gate on.
type metricKind int

const (
	metricFrames metricKind = iota
	metricTranscripts
	metricSentImage
	metricSentAudioBytes
	metricGestures
	metricTts
	metricResults
)

// expectation is ONE parsed `key=value` or `key-min=value` assertion.
//
// `key=value` requires an EXACT match; `key-min=value` requires at least
// `value`. The minimum form exists so a scenario that forwards several
// transcripts can assert "not empty" without pinning an implementation-defined
// count.
type expectation struct {
	metric  metricKind
	name    string
	want    int
	minimum bool
}

// expectations is the whole --expect gate. An empty slice disables the gate.
type expectations []expectation

// parseExpectations parses `k=v,k2-min=v2,...`. An empty spec disables the
// gate. Invalid syntax, unknown keys, and negative values are usage errors.
func parseExpectations(spec string) (expectations, error) {
	if strings.TrimSpace(spec) == "" {
		return nil, nil
	}

	parts := strings.Split(spec, ",")
	parsed := make(expectations, 0, len(parts))
	seen := make(map[string]struct{}, len(parts))
	for _, part := range parts {
		token := strings.TrimSpace(part)
		if token == "" {
			return nil, fmt.Errorf("--expect contains an empty assertion in %q", spec)
		}

		key, valueText, found := strings.Cut(token, "=")
		if !found {
			return nil, fmt.Errorf("--expect assertion %q must be key=value", token)
		}
		key = strings.TrimSpace(key)
		valueText = strings.TrimSpace(valueText)
		if key == "" || valueText == "" {
			return nil, fmt.Errorf("--expect assertion %q must be key=value", token)
		}

		metric, name, minimum, err := parseExpectationKey(key)
		if err != nil {
			return nil, err
		}
		want, err := strconv.Atoi(valueText)
		if err != nil {
			return nil, fmt.Errorf("--expect %s: %q is not an integer", key, valueText)
		}
		if want < 0 {
			return nil, fmt.Errorf("--expect %s must not be negative, got %d", key, want)
		}

		dedupe := fmt.Sprintf("%d/%t", metric, minimum)
		if _, duplicate := seen[dedupe]; duplicate {
			return nil, fmt.Errorf("--expect repeats %s", key)
		}
		seen[dedupe] = struct{}{}

		parsed = append(parsed, expectation{metric: metric, name: name, want: want, minimum: minimum})
	}
	return parsed, nil
}

// parseExpectationKey maps the CLI spelling (including aliases) onto a counter.
// A `-min` suffix switches the assertion from exact to minimum.
func parseExpectationKey(key string) (metricKind, string, bool, error) {
	name := strings.ToLower(key)
	minimum := false
	if strings.HasSuffix(name, "-min") {
		minimum = true
		name = strings.TrimSuffix(name, "-min")
	}

	switch name {
	case "frame", "frames":
		return metricFrames, "frames", minimum, nil
	case "transcript", "transcripts":
		return metricTranscripts, "transcripts", minimum, nil
	case "image", "sent_image", "sent-image":
		return metricSentImage, "sent_image", minimum, nil
	case "audio-bytes", "sent_audio_bytes", "sent-audio-bytes":
		return metricSentAudioBytes, "sent_audio_bytes", minimum, nil
	case "gesture", "gestures":
		return metricGestures, "gestures", minimum, nil
	case "tts":
		return metricTts, "tts", minimum, nil
	case "result", "results":
		return metricResults, "results", minimum, nil
	default:
		return 0, "", false, fmt.Errorf("--expect has unknown key %q", key)
	}
}

// actual reads the counter this assertion gates on.
func (e expectation) actual(result scenarioResult) int {
	switch e.metric {
	case metricFrames:
		return result.frames
	case metricTranscripts:
		return result.transcripts
	case metricSentImage:
		return result.sentImage
	case metricSentAudioBytes:
		return result.sentAudioBytes
	case metricGestures:
		return result.gestures
	case metricTts:
		return result.tts
	case metricResults:
		return result.results
	default:
		return 0
	}
}

// check reports EVERY failed assertion, not just the first, so a failing E2E
// run explains the whole shortfall in one line.
func (set expectations) check(result scenarioResult) error {
	var failures []string
	for _, expected := range set {
		got := expected.actual(result)
		ok := got == expected.want
		comparator := "=="
		if expected.minimum {
			ok = got >= expected.want
			comparator = ">="
		}
		if !ok {
			failures = append(
				failures,
				fmt.Sprintf("%s expected %s %d, got %d", expected.name, comparator, expected.want, got),
			)
		}
	}
	if len(failures) == 0 {
		return nil
	}
	return fmt.Errorf("%s", strings.Join(failures, "; "))
}
