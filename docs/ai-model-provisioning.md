# AI model provisioning

The hub's device adapters load their models from
`context.getExternalFilesDir(null)/models`. On a normal Android device that resolves
to:

```text
/storage/emulated/0/Android/data/com.glassstorm.phonemanager/files/models
```

`adb` also accepts the `/sdcard/Android/data/...` spelling of the same directory.
`DeviceAiModule` resolves every engine independently under this root. If a file is
missing, that one engine falls back to the deterministic `:adapter:jvm` fake. The
real engines also require an `arm64-v8a` device, because ai-android's native build
is arm64 only.

## Target tree

```text
models/
├── asr/
│   ├── model.int8.onnx
│   └── tokens.txt
├── diarization/
│   ├── 3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx
│   └── sherpa-onnx-pyannote-segmentation-3-0/
│       └── model.int8.onnx
├── gesture/
│   └── gesture_recognizer.task
├── shuwa/
│   ├── holistic_landmarker.task
│   ├── shuwa-knn-hksl.bin
│   └── shuwa-translator.tflite
└── tts/
    └── vits-cantonese-hf-xiaomaiiwn/
        ├── vits-cantonese-hf-xiaomaiiwn.onnx
        ├── lexicon.txt
        ├── rule.fst
        └── tokens.txt
```

The adapter only needs these listed files. Extra files from an upstream archive
(sample WAVs, Python helpers, READMEs) are harmless but are not required.

### The `shuwa/` tree (HKSL sequence recognition)

`shuwa/` holds the Shuwa sequence recogniser, which supersedes `gesture/` when it is
complete. All three files are required together; a partial tree is treated as
absent so the hub falls back to the single-image recogniser rather than starting a
windowed model with a missing half.

- `holistic_landmarker.task` is Google's MediaPipe Holistic bundle (Apache-2.0).
- `shuwa-translator.tflite` is the f32 export of the Shuwa HKSL translator.
- `shuwa-knn-hksl.bin` is the HKSL-only KNN table (`SKS1`, float16, 71 glosses).

Both Shuwa files are produced by the `Code/shuwa` toolkit checkout
(`build/shuwa-translator-f32.tflite`, `build/shuwa-knn-hksl.bin`) and are **renamed**
on the device: the adapter looks for the fixed names above, not for the export's
`-f32` suffix. The f16 export is half the size but was not validated on the phone,
so the runbook pins the f32 numbers.

## Fetch and verify on the host

```bash
scripts/fetch-ai-models.sh
```

The default destination is `build/ai-models`. Pass a path to stage somewhere else:

```bash
scripts/fetch-ai-models.sh /tmp/ai-models
```

The script downloads each engine, checks every byte against the pinned SHA-256
below, and fails loudly on a mismatch. It is idempotent: a file that already
matches is not downloaded again.

It does **not** fetch `shuwa/`: those two artefacts are exported locally by the
`Code/shuwa` toolkit, so stage them next to the downloaded tree before pushing:

```bash
mkdir -p build/ai-models/shuwa
cp ../shuwa/build/shuwa-knn-hksl.bin build/ai-models/shuwa/
cp ../shuwa/build/shuwa-translator-f32.tflite build/ai-models/shuwa/shuwa-translator.tflite
```

`holistic_landmarker.task` is the one downloadable Shuwa file; fetch it from the
MediaPipe URL in the pinned table.

## Push to the device

```bash
adb shell mkdir -p /sdcard/Android/data/com.glassstorm.phonemanager/files/models
adb push build/ai-models/. /sdcard/Android/data/com.glassstorm.phonemanager/files/models/
```

The trailing `/.` on the source is deliberate: it merges the tree into `models/`
instead of creating a nested `models/ai-models/` directory.

If direct pushes into `Android/data` are blocked on the target device, copy through
the app's own uid:

```bash
adb push build/ai-models /data/local/tmp/ai-models
adb shell run-as com.glassstorm.phonemanager \
  sh -c 'mkdir -p /sdcard/Android/data/com.glassstorm.phonemanager/files/models && \
         cp -r /data/local/tmp/ai-models/. /sdcard/Android/data/com.glassstorm.phonemanager/files/models/'
```

Verify what landed on the device:

```bash
adb shell 'cd /sdcard/Android/data/com.glassstorm.phonemanager/files/models && \
  sha256sum asr/model.int8.onnx asr/tokens.txt \
    diarization/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx \
    diarization/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx \
    gesture/gesture_recognizer.task \
    shuwa/holistic_landmarker.task \
    shuwa/shuwa-translator.tflite \
    shuwa/shuwa-knn-hksl.bin \
    tts/vits-cantonese-hf-xiaomaiiwn/vits-cantonese-hf-xiaomaiiwn.onnx \
    tts/vits-cantonese-hf-xiaomaiiwn/tokens.txt \
    tts/vits-cantonese-hf-xiaomaiiwn/lexicon.txt \
    tts/vits-cantonese-hf-xiaomaiiwn/rule.fst'
```

## Pinned files

| Engine      | Target path                                                          | Source                                                                                                                                  | SHA-256                                                            |
| ----------- | -------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| ASR         | `asr/model.int8.onnx`                                                | `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2` (GitHub release, see below)                                           | `12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d` |
| ASR         | `asr/tokens.txt`                                                     | same archive                                                                                                                            | `f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc` |
| TTS         | `tts/vits-cantonese-hf-xiaomaiiwn/vits-cantonese-hf-xiaomaiiwn.onnx` | `https://huggingface.co/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/vits-cantonese-hf-xiaomaiiwn.onnx`                         | `7d8d4f5550b607999417a99131b034c8cc2b8dca69f9e9fdefeff6655643c139` |
| TTS         | `tts/vits-cantonese-hf-xiaomaiiwn/tokens.txt`                        | `https://huggingface.co/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/tokens.txt`                                                | `e6c853092480206c9611101c1eb82d930f07d79e1d399bf9ca5b6acfaf443af3` |
| TTS         | `tts/vits-cantonese-hf-xiaomaiiwn/lexicon.txt`                       | `https://huggingface.co/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/lexicon.txt`                                               | `337845cd7ea6221e69684329beda84198dc8678699a4936670a08a9449c84acb` |
| TTS         | `tts/vits-cantonese-hf-xiaomaiiwn/rule.fst`                          | `https://huggingface.co/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main/rule.fst`                                                  | `743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd` |
| Diarization | `diarization/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx`  | `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-segmentation-models/sherpa-onnx-pyannote-segmentation-3-0.tar.bz2`     | `d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d` |
| Diarization | `diarization/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx`     | `https://github.com/k2-fsa/sherpa-onnx/releases/download/speaker-recongition-models/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx` | `f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11` |
| Gesture     | `gesture/gesture_recognizer.task`                                    | `https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/gesture_recognizer.task`               | `01e5a59bcb2c9a6f13b76d67f038e417c07341250899199a409677b3e2900bb1` |
| Shuwa       | `shuwa/holistic_landmarker.task`                                     | `https://storage.googleapis.com/mediapipe-models/holistic_landmarker/holistic_landmarker/float16/1/holistic_landmarker.task`              | `e2dab61191e2dcd0a15f943d8e3ed1dce13c82dfa597b9dd39f562975a50c3f8` |
| Shuwa       | `shuwa/shuwa-translator.tflite`                                      | local export, `Code/shuwa/build/shuwa-translator-f32.tflite` (renamed on the device)                                                      | `9f1c5017cdf95002e8c156308757b56d989db32c8a573c864030c4f053240449` |
| Shuwa       | `shuwa/shuwa-knn-hksl.bin`                                           | local export, `Code/shuwa/build/shuwa-knn-hksl.bin` (`SKS1`, 71 glosses, 21,181 vectors)                                                  | `38cd07093eb3ded260bf0f6b3ed79fea113ec794b5213cc21edd37e49997d61a` |

Container archives, for manual extraction:

| Archive                                                                   | SHA-256                                                            |
| ------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2`         | `7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63` |
| `sherpa-onnx-pyannote-segmentation-3-0.tar.bz2`                           | `24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488` |
| `vits-cantonese-hf-xiaomaiiwn.tar.bz2` (GitHub tarball, alternate source) | `bf3013cd4be34f531b7e514e708d835584dd60c9ad6eaf467ac1402005c04e46` |

The ASR archive is
`https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2`.
Extract only the two needed files:

```bash
tar xjf sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2 \
  --strip-components=1 \
  sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/model.int8.onnx \
  sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09/tokens.txt
```

The GitHub TTS tarball is not used by the script because its `lexicon.txt` is an
older revision (`4099e3a825ce8d742bc6a58801e728539a1cd819f2a36f337d39c28e52950d53`).
The script pins the current Hugging Face files instead, including the current
lexicon. The model, tokens and rule FST are byte-identical in both sources.

## Licences

| Component                      | Licence                                                                                                                        |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------------------------ |
| SenseVoice ASR (WSYue variant) | Apache-2.0 per the `ASLP-lab/WSYue-ASR` model card. The 2025 release archive ships no LICENSE file.                            |
| Cantonese VITS (xiaomaiiwn)    | No licence is declared by the upstream model author. Treat it as unlicensed and local-evaluation-only; do not redistribute it. |
| pyannote segmentation 3.0      | MIT                                                                                                                            |
| 3D-Speaker CAM++               | Apache-2.0                                                                                                                     |
| MediaPipe gesture recognizer   | Apache-2.0                                                                                                                     |
| MediaPipe holistic landmarker  | Apache-2.0                                                                                                                     |
| Shuwa translator + HKSL table  | No licence is declared by the Shuwa toolkit or its sample data. Built locally, declared `UserProvided`, and must not be redistributed. |

## Troubleshooting

- `DeviceAiModule` binds the fakes when the device is not `arm64-v8a`, even if the
  files exist. Check `adb shell getprop ro.product.cpu.abilist`.
- The ASR check accepts any `asr/model*.onnx`, but this runbook pins
  `model.int8.onnx`. A different filename is accepted by the adapter but is not
  covered by the hashes above.
- A failed hash on the device almost always means a truncated `adb push`. Delete
  the file and push again.
- If `shuwa/` is present but signs are never reported, check that all THREE files
  landed: a partial tree falls back to the single-image recogniser silently (by
  design). `adb shell ls -l .../models/shuwa` must list the task, the translator
  and the KNN table.
- The Shuwa port reports nothing while the camera is still: the engine's motion gate
  skips windows whose pose barely changed. A held-but-static hand is still a
  *moving* sign only up to the point the reference model expects; sign the gloss
  through its full movement rather than holding the final pose.
