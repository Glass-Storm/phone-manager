#!/usr/bin/env bash
# Provision the ai-android model files the hub's device adapter needs, verifying every
# byte against a pinned SHA-256. Fetches all four engine trees: ASR (SenseVoice int8),
# TTS (Cantonese VITS), diarization (pyannote segmentation + 3D-Speaker CAM++), and
# gesture (MediaPipe).
#
# Usage: scripts/fetch-ai-models.sh [DEST_DIR]
#   DEST_DIR defaults to ./build/ai-models
#
# Layout produced (the directories DeviceAiModule resolves under
# <externalFilesDir>/models):
#   DEST_DIR/asr/model.int8.onnx
#   DEST_DIR/asr/tokens.txt
#   DEST_DIR/tts/vits-cantonese-hf-xiaomaiiwn/vits-cantonese-hf-xiaomaiiwn.onnx
#   DEST_DIR/tts/vits-cantonese-hf-xiaomaiiwn/tokens.txt
#   DEST_DIR/tts/vits-cantonese-hf-xiaomaiiwn/lexicon.txt
#   DEST_DIR/tts/vits-cantonese-hf-xiaomaiiwn/rule.fst
#   DEST_DIR/diarization/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx
#   DEST_DIR/diarization/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx
#   DEST_DIR/gesture/gesture_recognizer.task
#
# Push the tree to the app's external files dir (see docs/ai-model-provisioning.md):
#   adb shell mkdir -p /sdcard/Android/data/com.glassstorm.phonemanager/files/models
#   adb push DEST_DIR/. /sdcard/Android/data/com.glassstorm.phonemanager/files/models/
set -euo pipefail

DEST="${1:-build/ai-models}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "${DEST}" = /* ]] || DEST="${ROOT}/${DEST}"

GH="https://github.com/k2-fsa/sherpa-onnx/releases/download"

# --- ASR: SenseVoice int8 (Apache-2.0, converted from ASLP-lab/WSYue-ASR) ------------------
ASR_DIR="${DEST}/asr"
ASR_BUNDLE="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09.tar.bz2"
ASR_BUNDLE_DIR="sherpa-onnx-sense-voice-zh-en-ja-ko-yue-int8-2025-09-09"
ASR_MODEL_FILE="model.int8.onnx"
ASR_TOKENS_FILE="tokens.txt"
ASR_BUNDLE_SHA256="7305f7905bfcf77fa0b39388a313f3da35c68d971661a65475b56fb2162c8e63"
ASR_MODEL_SHA256="12ca1a2ae7ecf3e0019ef2822307ee0b5cadc9196569e379b4c4026f8205276d"
ASR_TOKENS_SHA256="f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc"
ASR_URL="${GH}/asr-models/${ASR_BUNDLE}"

# --- TTS: Cantonese VITS (upstream license not declared; local evaluation only) ------------
TTS_DIR="${DEST}/tts/vits-cantonese-hf-xiaomaiiwn"
TTS_BASE_URL="https://huggingface.co/csukuangfj/vits-cantonese-hf-xiaomaiiwn/resolve/main"
TTS_MODEL_FILE="vits-cantonese-hf-xiaomaiiwn.onnx"
TTS_MODEL_SHA256="7d8d4f5550b607999417a99131b034c8cc2b8dca69f9e9fdefeff6655643c139"
TTS_TOKENS_FILE="tokens.txt"
TTS_TOKENS_SHA256="e6c853092480206c9611101c1eb82d930f07d79e1d399bf9ca5b6acfaf443af3"
TTS_LEXICON_FILE="lexicon.txt"
TTS_LEXICON_SHA256="337845cd7ea6221e69684329beda84198dc8678699a4936670a08a9449c84acb"
TTS_RULE_FILE="rule.fst"
TTS_RULE_SHA256="743f402181fcfebf76cc2f0546b71fa26476e626fbe4e460fb7b4c3a7a8bd5bd"

# --- Gesture: MediaPipe (Apache-2.0) -------------------------------------------------------
GESTURE_DIR="${DEST}/gesture"
GESTURE_FILE="gesture_recognizer.task"
GESTURE_SHA256="01e5a59bcb2c9a6f13b76d67f038e417c07341250899199a409677b3e2900bb1"
GESTURE_URL="https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/${GESTURE_FILE}"

# --- Diarization: pyannote/segmentation-3.0 (MIT) + 3D-Speaker CAM++ (Apache-2.0) ----------
DIAR_DIR="${DEST}/diarization"
SEG_BUNDLE="sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
SEG_DIR="sherpa-onnx-pyannote-segmentation-3-0"
SEG_FILE="model.int8.onnx"
SEG_BUNDLE_SHA256="24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488"
SEG_FILE_SHA256="d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d"

EMB_FILE="3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
EMB_SHA256="f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11"

SEG_URL="${GH}/speaker-segmentation-models/${SEG_BUNDLE}"
EMB_URL="${GH}/speaker-recongition-models/${EMB_FILE}"

mkdir -p "${GESTURE_DIR}" "${ASR_DIR}/tmp" "${TTS_DIR}" "${DIAR_DIR}/tmp"

verify() { # <sha256> <file>
    echo "${1}  ${2}" | sha256sum -c - >/dev/null
}

# Download <url> to <file> only when it is absent or fails its pinned <sha256>, and always
# re-verify. A mismatched file is deleted first so a corrupt prefix can never self-heal.
fetch_verified() { # <sha256> <url> <file>
    if [[ -f "${3}" ]] && verify "${1}" "${3}"; then
        return 0
    fi
    rm -f "${3}"
    echo "Downloading ${2}"
    curl -fL --retry 3 -o "${3}" "${2}"
    verify "${1}" "${3}"
}

echo "Fetching ai-android models into ${DEST}"

# --- Gesture recognizer (MediaPipe, Apache-2.0) --------------------------------------------
fetch_verified "${GESTURE_SHA256}" "${GESTURE_URL}" "${GESTURE_DIR}/${GESTURE_FILE}"

# --- ASR (SenseVoice int8, Apache-2.0) -----------------------------------------------------
if ! {
    verify "${ASR_MODEL_SHA256}" "${ASR_DIR}/${ASR_MODEL_FILE}" &&
        verify "${ASR_TOKENS_SHA256}" "${ASR_DIR}/${ASR_TOKENS_FILE}"
}; then
    bundle="${ASR_DIR}/tmp/${ASR_BUNDLE}"
    fetch_verified "${ASR_BUNDLE_SHA256}" "${ASR_URL}" "${bundle}"
    tar xjf "${bundle}" -C "${ASR_DIR}" --strip-components=1 \
        "${ASR_BUNDLE_DIR}/${ASR_MODEL_FILE}" "${ASR_BUNDLE_DIR}/${ASR_TOKENS_FILE}"
fi
verify "${ASR_MODEL_SHA256}" "${ASR_DIR}/${ASR_MODEL_FILE}"
verify "${ASR_TOKENS_SHA256}" "${ASR_DIR}/${ASR_TOKENS_FILE}"

# --- TTS voice (Cantonese VITS; upstream license not declared) -----------------------------
fetch_verified "${TTS_MODEL_SHA256}" "${TTS_BASE_URL}/${TTS_MODEL_FILE}" "${TTS_DIR}/${TTS_MODEL_FILE}"
fetch_verified "${TTS_TOKENS_SHA256}" "${TTS_BASE_URL}/${TTS_TOKENS_FILE}" "${TTS_DIR}/${TTS_TOKENS_FILE}"
fetch_verified "${TTS_LEXICON_SHA256}" "${TTS_BASE_URL}/${TTS_LEXICON_FILE}" "${TTS_DIR}/${TTS_LEXICON_FILE}"
fetch_verified "${TTS_RULE_SHA256}" "${TTS_BASE_URL}/${TTS_RULE_FILE}" "${TTS_DIR}/${TTS_RULE_FILE}"

# --- Speaker segmentation (pyannote/segmentation-3.0, MIT) ---------------------------------
bundle="${DIAR_DIR}/tmp/${SEG_BUNDLE}"
fetch_verified "${SEG_BUNDLE_SHA256}" "${SEG_URL}" "${bundle}"
tar xjf "${bundle}" -C "${DIAR_DIR}" "${SEG_DIR}/${SEG_FILE}"
verify "${SEG_FILE_SHA256}" "${DIAR_DIR}/${SEG_DIR}/${SEG_FILE}"

# --- Speaker embedding (3D-Speaker CAM++, Apache-2.0) --------------------------------------
fetch_verified "${EMB_SHA256}" "${EMB_URL}" "${DIAR_DIR}/${EMB_FILE}"

rm -rf "${ASR_DIR}/tmp" "${DIAR_DIR}/tmp"
echo "OK: ai-android models verified under ${DEST}"
