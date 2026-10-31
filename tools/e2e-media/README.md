# E2E media presets

Small, checked-in fixtures for driving the Go reference peer (`tools/mockpeer`)
and the glasses debug page against a real or loopback hub. They are test inputs,
not product assets.

| File               | Size      | Format                          | SHA-256                                                            |
| ------------------ | --------- | ------------------------------- | ------------------------------------------------------------------ |
| `thumbs-up.jpg`    | 343,982 B | JPEG, 864 × 1234, baseline      | `6f05c99a19e98231104858e4a312c7977128afcec811a0bb1b1619fe56dbfa9f` |
| `thumbs-up-01.jpg` | 311,858 B | JPEG, 960 × 1161, baseline      | `fd0c8a318457fb4481e5fb07cf19c63e997db6fafe4d38b324de76cdb2037ca5` |
| `thumb-up.jpg`     | 156,970 B | JPEG, 960 × 1067, baseline      | `78b38d4ba7b6de2f59c524a5cdf99e9a28b79d3d7c120b943ea87171f6d5d033` |
| `demo-yue.wav`     | 164,780 B | WAV, 16 kHz mono PCM16, 5.148 s | `0960b2db54ae202071d250e6462fbf74a3c863f0e3e7f01273e4939c996875a0` |

## Standard gesture preset

`thumbs-up.jpg` is the standard preset for the gesture loop. Send it as the
daemon's still:

```bash
go run ./tools/mockpeer --addr 127.0.0.1:50051 --role daemon \
  --image tools/e2e-media/thumbs-up.jpg
```

The expected hub gesture result is `謝謝`. `thumbs-up-01.jpg` and `thumb-up.jpg`
are alternate captures of the same gesture for lighting and angle variation.

### Provenance and licence

These three JPEGs were recovered from the untracked `.dev/e2e/` development
scratch tree. No upstream URL, author, or licence was recorded with them, and no
licence can be established from the files themselves. They are kept only as
local test fixtures. Do not redistribute them outside this project; if you need
a redistributable preset, replace them with a capture you own or a licensed
stock photo and update the hashes in this table.

To generate a replacement from a video you own:

```bash
ffmpeg -i thumbs-up.mp4 -frames:v 1 -q:v 2 tools/e2e-media/thumbs-up.jpg
```

Then confirm the MediaPipe gesture engine still reports `謝謝` before updating
the hash. The mock peer requires valid JPEG SOI (`FF D8`) and EOI (`FF D9`)
markers.

## Cantonese speech preset

`demo-yue.wav` is the fixture for the speech-to-text and diarization loop. It is
byte-identical to `test_wavs/yue.wav` in the sherpa-onnx SenseVoice release.

| Property         | Value                                                                                                                      |
| ---------------- | -------------------------------------------------------------------------------------------------------------------------- |
| Duration         | 5.148 s                                                                                                                    |
| Sample rate      | 16,000 Hz                                                                                                                  |
| Channels         | 1 (mono)                                                                                                                   |
| Sample format    | signed 16-bit little-endian PCM                                                                                            |
| Trailing silence | about 0.843 s after the last sample above amplitude 300                                                                    |
| Source           | `test_wavs/yue.wav` in the sherpa-onnx SenseVoice bundle                                                                   |
| Licence          | Apache-2.0 per the `ASLP-lab/WSYue-ASR` model card the 2025 bundle points at; the older 2024 bundle points at FunASR (MIT) |

The trailing silence is load-bearing: it is what closes the hub's utterance, so
trimming the file changes the test behaviour. Keep the full 5.148 s.

### Regenerate

```bash
curl -fL -o /tmp/asr.tar.bz2 \
  https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2
tar xjf /tmp/asr.tar.bz2 -C /tmp --strip-components=1 \
  sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/test_wavs/yue.wav
cp /tmp/test_wavs/yue.wav tools/e2e-media/demo-yue.wav
sha256sum tools/e2e-media/demo-yue.wav
```

The archive SHA-256 is
`7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63`. The same
`yue.wav` is also present in the 2024-07-17 int8 bundle with the same SHA-256.

Send it as the glasses-side audio:

```bash
go run ./tools/mockpeer --addr 127.0.0.1:50051 --role glass \
  --audio-file tools/e2e-media/demo-yue.wav
```
