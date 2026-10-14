#!/usr/bin/env bash
# Provision the ai-android model files the hub's device adapter needs, verifying every
# byte against a pinned SHA-256. Only the gesture recognizer and the two diarization
# ONNX graphs are fetched here; ASR/TTS voices are larger and unrelated to T4.
#
# Usage: scripts/fetch-ai-models.sh [DEST_DIR]
#   DEST_DIR defaults to ./build/ai-models
#
# Layout produced (the directories the adapters are constructed with):
#   DEST_DIR/gesture/gesture_recognizer.task
#   DEST_DIR/diarization/sherpa-onnx-pyannote-segmentation-3-0/model.int8.onnx
#   DEST_DIR/diarization/3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx
#
# Copy the tree to an app-private dir on an arm64 device, e.g.:
#   adb push DEST_DIR /data/local/tmp/ai-models
#   adb shell run-as com.glassstorm.phonemanager cp -r /data/local/tmp/ai-models files/ai-models
set -euo pipefail

DEST="${1:-build/ai-models}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ "${DEST}" = /* ]] || DEST="${ROOT}/${DEST}"

GESTURE_DIR="${DEST}/gesture"
GESTURE_FILE="gesture_recognizer.task"
GESTURE_SHA256="01e5a59bcb2c9a6f13b76d67f038e417c07341250899199a409677b3e2900bb1"
GESTURE_URL="https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/1/${GESTURE_FILE}"

DIAR_DIR="${DEST}/diarization"
SEG_BUNDLE="sherpa-onnx-pyannote-segmentation-3-0.tar.bz2"
SEG_DIR="sherpa-onnx-pyannote-segmentation-3-0"
SEG_FILE="model.int8.onnx"
SEG_BUNDLE_SHA256="24615ee884c897d9d2ba09bb4d30da6bb1b15e685065962db5b02e76e4996488"
SEG_FILE_SHA256="d582f4b4c6b48205de7e0643c57df0df5615a3c176189be3fc461e9d18827b5d"

EMB_FILE="3dspeaker_speech_campplus_sv_zh-cn_16k-common.onnx"
EMB_SHA256="f682b514c05d947ee3fa91cd6ec6c5c7543479a128373fa29b1faedccd21fd11"

GH="https://github.com/k2-fsa/sherpa-onnx/releases/download"
SEG_URL="${GH}/speaker-segmentation-models/${SEG_BUNDLE}"
EMB_URL="${GH}/speaker-recongition-models/${EMB_FILE}"

mkdir -p "${GESTURE_DIR}" "${DIAR_DIR}/tmp"

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

# --- Speaker segmentation (pyannote/segmentation-3.0, MIT) ---------------------------------
bundle="${DIAR_DIR}/tmp/${SEG_BUNDLE}"
fetch_verified "${SEG_BUNDLE_SHA256}" "${SEG_URL}" "${bundle}"
tar xjf "${bundle}" -C "${DIAR_DIR}" "${SEG_DIR}/${SEG_FILE}"
verify "${SEG_FILE_SHA256}" "${DIAR_DIR}/${SEG_DIR}/${SEG_FILE}"

# --- Speaker embedding (3D-Speaker CAM++, Apache-2.0) --------------------------------------
fetch_verified "${EMB_SHA256}" "${EMB_URL}" "${DIAR_DIR}/${EMB_FILE}"

rm -rf "${DIAR_DIR}/tmp"
echo "OK: ai-android models verified under ${DEST}"
