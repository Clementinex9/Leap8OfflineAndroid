# Offline pronunciation: Kokoro v1.0 HD Hybrid & C++20 DSP (0.5.4)

Leap 8 uses **Kokoro-82M v1.0** in a **0.5.4 hybrid precision graph (`model.onnx`, 163,902,037 bytes)**
paired with a **C++20 `enhanceSpeechPcm` studio mastering chain**, not Piper LJSpeech, for
offline pronunciation. No API key, network request, or Android system voice download is needed.
The existing sherpa-onnx 1.13.2 AAR runs Kokoro inside `:leap8_speech`, isolated from the learning
UI and GGUF runtime, and probes NNAPI with a `450 ms` warm-up latency gate before falling back
to 4-thread CPU synthesis.

## 0.5.4 HD vocoder reconstruction & acoustic DSP

Pure int8 `DynamicQuantizeLinear` + `ConvInteger` operators inside `/decoder/generator/` produce
audible `4.8 kHz` and `9.6 kHz` whistling harmonics as well as muffled mid-band formants on ARM
devices. In `0.5.4`:

- All `100` vocoder operators under `/decoder/generator/` are dequantized back to native **FP32**
  (`model.onnx`, replacing `model.int8.onnx`), while lightweight frontend modules stay quantized.
- Every synthesized 24 kHz float PCM clip passes through C++20 `Leap8CoreNative.enhanceSpeechPcm`
  (`libleap8_core.so`): DC removal + 75 Hz high-pass, `-1.2 dB @ 240 Hz` de-mud, `+3.0 dB @ 2.6 kHz`
  presence lift, `-10.0 dB (Q=12) @ 4.8 kHz` narrow whistle notch, split-band `5.8 kHz` de-esser,
  `-5.0 dB @ 8.0 kHz` silk shelf, 4th-order `9.0 kHz` Butterworth low-pass, `0.80f` C1 soft-knee
  limiter, and `10 ms / 18 ms` cosine fades.
- Natural inter-clause silence scaling (`0.62x`) trims stiff pauses while preserving natural
  stress timing.

## Provenance and compatibility

- Model upstream: <https://huggingface.co/hexgrad/Kokoro-82M> (Apache-2.0 weights).
- sherpa model documentation and speaker mapping:
  <https://k2-fsa.github.io/sherpa/onnx/tts/pretrained_models/kokoro.html>.
- Upstream base archive:
  <https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-int8-multi-lang-v1_0.tar.bz2>.
- Upstream archive size: **132,353,410 bytes**; SHA-256:
  `72c347230107ef8a31098ec4d468a605244beef5f1ed00db97efa13518800653`.
- Upstream int8 STFT fix reference (`4b86207e...` crash/NaN avoidance):
  <https://github.com/k2-fsa/sherpa-onnx/pull/4087> (Leap 8 `0.5.4` further dequantizes all
  `/decoder/generator/*` vocoder layers in `model.onnx` for whistle-free hi-fi waveform synthesis).
- Kotlin 1.13.2 API:
  <https://github.com/k2-fsa/sherpa-onnx/blob/v1.13.2/sherpa-onnx/kotlin-api/Tts.kt>.

`tools/download-kokoro.ps1` downloads the official upstream archive in 1 MiB pieces and verifies
its SHA-256 when re-bootstrapping base voice assets. A CDN address override is request-scoped only;
it does not modify Windows DNS, proxy settings, or hosts files.

## Voice preferences

`VoiceCatalog.voices` exposes eight named US/UK voices with their exact v1.0 speaker IDs.
The default is Heart (`af_heart`, speaker 3). `NaturalVoice.configure(voiceId, speed)`
saves global preferences to `natural_voice` (`voice_id`, `speed`). Every utterance reads
those preferences, including from an already-open learning screen. `currentVoiceId`
and `currentSpeed` expose the saved settings. Normal speed is clamped to 0.7–1.3×;
the existing slow button applies an additional 0.82 factor with a 0.6× lower bound.

US voices use the packaged US Misaki lexicon. The pinned sherpa-onnx 1.13.2 piper
frontend calls `espeak_SetVoiceByName`, not language-alias selection: canonical voice
names are `en-us` (US) and `en` (UK). In particular, do not pass the language alias
`en-gb`; this build's embedded eSpeak data rejects that name and can abort in native
code. UK voices use canonical `en` so pronunciation follows British phonemes as well
as timbre. All eight voices have a device regression entry; actual playback pass/fail
is recorded in the release validation report rather than assumed from initialization.
Audio remains native 24 kHz float PCM; changing speed occurs during synthesis, not by
pitch-shifting playback. A memory-only cache holds up to 60 seconds of repeated word or
short-phrase audio (~5.5 MiB). It is keyed by text, voice and speed, and never stores text
on disk. Every generated sample is checked for NaN/infinity, silence and extreme clipping.

The system offline TTS fallback is constructed only after the isolated Kokoro service
actually fails; merely opening a page initializes neither native neural TTS nor system TTS.
Initial requests remain queued while the voice service loads.

## Verification

Host-only tests: `VoiceCatalogTest`, `VoiceAudioGuardTest`, and `Leap8CoreNativeTest`
(covering audio guards, pause scaling, and C++20 `enhanceSpeechPcm` DSP filtering).
Device test: `NaturalVoiceProcessIpcTest` exercises the real isolated service, both the
default word and a British sentence at 0.85×. It requires the played reply to contain the
selected voice ID, non-empty samples and a 24 kHz sample rate. Log tag `Leap8Voice`
records voice, effective speed, sample count, cache hit and synthesis latency; it does
not log the learning text. Passing compilation alone is not proof of device playback.

eSpeak NG phonemization data remains GPL-3.0; retain its packaged license and assess
native redistribution obligations before production distribution. Changing the voice
model does not remove the pre-existing eSpeak licensing consideration.
