"""Add sherpa-onnx's required metadata to the bundled Piper ONNX model.

The original Piper release stores voice metadata beside the model in an ONNX JSON
file. sherpa-onnx 1.13.2 reads it from the ONNX ModelProto metadata_props field.
Only top-level protobuf metadata is changed; the graph bytes are verified intact.

Usage: python tools/prepare_piper_model.py
Source: https://k2-fsa.github.io/sherpa/onnx/tts/piper.html
Schema: https://github.com/onnx/onnx/blob/main/onnx/onnx.proto
"""

from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import tempfile


ROOT = Path(__file__).resolve().parents[1]
VOICE = ROOT / "app/src/main/assets/vits-piper-en_US-ljspeech-medium"
MODEL = VOICE / "en_US-ljspeech-medium.onnx"
CONFIG = VOICE / "en_US-ljspeech-medium.onnx.json"


def varint(value: int) -> bytes:
    if value < 0:
        raise ValueError("Only nonnegative protobuf values are supported")
    result = bytearray()
    while value > 0x7F:
        result.append((value & 0x7F) | 0x80)
        value >>= 7
    result.append(value)
    return bytes(result)


def read_varint(data: bytes, offset: int) -> tuple[int, int]:
    value = 0
    shift = 0
    while offset < len(data) and shift < 70:
        byte = data[offset]
        offset += 1
        value |= (byte & 0x7F) << shift
        if byte < 0x80:
            return value, offset
        shift += 7
    raise ValueError("Malformed protobuf varint")


def fields(data: bytes):
    """Yield (number, wire_type, field_start, payload_start, field_end)."""
    offset = 0
    while offset < len(data):
        start = offset
        tag, offset = read_varint(data, offset)
        number, wire = tag >> 3, tag & 7
        if number == 0:
            raise ValueError("Invalid protobuf field number")
        if wire == 0:
            _, end = read_varint(data, offset)
            payload_start = offset
        elif wire == 1:
            payload_start, end = offset, offset + 8
        elif wire == 2:
            length, payload_start = read_varint(data, offset)
            end = payload_start + length
        elif wire == 5:
            payload_start, end = offset, offset + 4
        else:
            raise ValueError(f"Unsupported protobuf wire type {wire}")
        if end > len(data):
            raise ValueError("Truncated protobuf field")
        yield number, wire, start, payload_start, end
        offset = end


def string_field(number: int, value: str) -> bytes:
    encoded = value.encode("utf-8")
    return varint((number << 3) | 2) + varint(len(encoded)) + encoded


def metadata_field(key: str, value: str) -> bytes:
    # ONNX ModelProto.metadata_props = field 14, and StringStringEntryProto has
    # key = field 1, value = field 2. Both messages use wire type 2.
    entry = string_field(1, key) + string_field(2, value)
    return varint((14 << 3) | 2) + varint(len(entry)) + entry


def inspect_model(data: bytes) -> tuple[dict[str, str], bytes]:
    metadata: dict[str, str] = {}
    graph_hash = None
    for number, wire, _start, payload_start, end in fields(data):
        if number == 7 and wire == 2:
            graph_hash = hashlib.sha256(data[payload_start:end]).digest()
        elif number == 14 and wire == 2:
            entry = data[payload_start:end]
            values: dict[int, str] = {}
            for item_number, item_wire, _, item_start, item_end in fields(entry):
                if item_wire == 2 and item_number in (1, 2):
                    values[item_number] = entry[item_start:item_end].decode("utf-8")
            if 1 in values and 2 in values:
                if values[1] in metadata:
                    raise ValueError(f"Duplicate ONNX metadata key: {values[1]}")
                metadata[values[1]] = values[2]
    if graph_hash is None:
        raise ValueError("ONNX ModelProto has no graph")
    return metadata, graph_hash


def main() -> None:
    config = json.loads(CONFIG.read_text(encoding="utf-8"))
    required = {
        "model_type": "vits",
        "comment": "piper",
        "language": config["language"]["name_english"],
        "voice": config["espeak"]["voice"],
        "has_espeak": "1",
        "n_speakers": str(config["num_speakers"]),
        "sample_rate": str(config["audio"]["sample_rate"]),
    }
    original = MODEL.read_bytes()
    existing, graph_hash = inspect_model(original)
    if all(existing.get(key) == value for key, value in required.items()):
        print("Piper ONNX metadata already matches sherpa-onnx requirements")
        return

    output = bytearray()
    for number, _wire, start, _payload_start, end in fields(original):
        if number != 14:
            output.extend(original[start:end])
    for key, value in sorted({**existing, **required}.items()):
        output.extend(metadata_field(key, value))

    converted = bytes(output)
    actual, new_graph_hash = inspect_model(converted)
    if new_graph_hash != graph_hash or any(actual.get(k) != v for k, v in required.items()):
        raise RuntimeError("Converted Piper model failed graph and metadata verification")
    with tempfile.NamedTemporaryFile(prefix="piper-metadata-", suffix=".onnx", dir=VOICE, delete=False) as temp:
        temp_path = Path(temp.name)
        temp.write(converted)
        temp.flush()
        os.fsync(temp.fileno())
    try:
        os.replace(temp_path, MODEL)
    finally:
        temp_path.unlink(missing_ok=True)
    print("Piper metadata:", actual)
    print("Graph SHA-256:", graph_hash.hex())
    print("Model SHA-256:", hashlib.sha256(converted).hexdigest())


if __name__ == "__main__":
    main()
