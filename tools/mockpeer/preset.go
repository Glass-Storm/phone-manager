package main

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"time"

	ecosysv1 "phone-manager/tools/mockpeer/gen/ecosys/v1"
)

const (
	audioFrameBytes  = audioSamplesPerFrame * 2
	audioFramePacing = 20 * time.Millisecond
)

// jpegSOI / jpegEOI are the two JPEG markers this peer insists on. The hub
// decodes the still, so shipping a non-JPEG blob would only surface as an
// opaque hub-side decode failure; reject it at the source instead.
var (
	jpegSOI = []byte{0xFF, 0xD8}
	jpegEOI = []byte{0xFF, 0xD9}
)

// loadImageFrame reads a JPEG still and wraps it in the frozen image frame.
func loadImageFrame(path string, ptsMs int64, width, height int32) (*ecosysv1.ImageFrame, error) {
	if width <= 0 || height <= 0 {
		return nil, fmt.Errorf("declared size %dx%d must be positive", width, height)
	}
	jpeg, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	if err := validateJPEG(jpeg); err != nil {
		return nil, err
	}
	return buildImageFrame(jpeg, ptsMs, width, height), nil
}

// validateJPEG rejects anything that is not framed by SOI (FF D8) and EOI
// (FF D9). The bytes stay otherwise opaque: the peer never parses segments.
func validateJPEG(jpeg []byte) error {
	if len(jpeg) < len(jpegSOI)+len(jpegEOI) {
		return fmt.Errorf("file is %d bytes, too short to be a JPEG", len(jpeg))
	}
	if !bytes.HasPrefix(jpeg, jpegSOI) {
		return fmt.Errorf("not a JPEG: missing SOI marker FF D8 (starts % X)", jpeg[:2])
	}
	if !bytes.HasSuffix(jpeg, jpegEOI) {
		return fmt.Errorf("not a JPEG: missing EOI marker FF D9 (ends % X)", jpeg[len(jpeg)-2:])
	}
	return nil
}

// buildImageFrame is the pure image construction used by loadImageFrame.
func buildImageFrame(jpeg []byte, ptsMs int64, width, height int32) *ecosysv1.ImageFrame {
	return &ecosysv1.ImageFrame{
		Jpeg:   jpeg,
		PtsMs:  ptsMs,
		Width:  width,
		Height: height,
	}
}

// chunkPCM16 splits raw PCM16 bytes into 20 ms / 320-sample frames. A trailing
// partial frame is kept so no byte is silently dropped.
func chunkPCM16(pcm []byte) [][]byte {
	if len(pcm) == 0 {
		return nil
	}
	frames := make([][]byte, 0, (len(pcm)+audioFrameBytes-1)/audioFrameBytes)
	for offset := 0; offset < len(pcm); offset += audioFrameBytes {
		end := offset + audioFrameBytes
		if end > len(pcm) {
			end = len(pcm)
		}
		frames = append(frames, pcm[offset:end])
	}
	return frames
}

// streamWAVAudio parses a WAV file, sends its PCM as paced audio frames, and
// reports frames sent plus total payload bytes.
func streamWAVAudio(
	ctx context.Context,
	stream ecosysv1.StreamService_OpenStreamClient,
	path string,
) (framesSent int, bytesSent int, err error) {
	pcm, err := readWAVFile(path)
	if err != nil {
		return 0, 0, err
	}

	frames := chunkPCM16(pcm)
	for index, frame := range frames {
		if index > 0 {
			if err := sleepContext(ctx, audioFramePacing); err != nil {
				return framesSent, bytesSent, err
			}
		}
		err := stream.Send(&ecosysv1.StreamFrame{
			Payload: &ecosysv1.StreamFrame_AudioPcm16_16K{AudioPcm16_16K: frame},
		})
		if err != nil {
			return framesSent, bytesSent, err
		}
		framesSent++
		bytesSent += len(frame)
	}

	seconds := float64(len(pcm)) / float64(audioSampleRateHz*2)
	fmt.Fprintf(
		os.Stderr,
		"audio-file bytes=%d frames=%d seconds=%.3f\n",
		len(pcm),
		framesSent,
		seconds,
	)
	return framesSent, bytesSent, nil
}

// sleepContext paces frames without ignoring the overall deadline.
func sleepContext(ctx context.Context, delay time.Duration) error {
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-timer.C:
		return nil
	}
}
