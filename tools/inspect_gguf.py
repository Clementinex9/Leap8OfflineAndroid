"""Read-only GGUF identity and structural checks; never executes model metadata."""
import argparse
import hashlib
import json
import math
import os
import struct


def inspect(path):
    size = os.path.getsize(path)
    with open(path, "rb") as f:
        def unpack(fmt):
            n = struct.calcsize(fmt)
            raw = f.read(n)
            if len(raw) != n:
                raise ValueError("Unexpected end of GGUF")
            return struct.unpack(fmt, raw)[0]

        def string(retain=True):
            n = unpack("<Q")
            if n > size - f.tell():
                raise ValueError("String exceeds file")
            if retain:
                return f.read(n).decode("utf-8", errors="strict")
            f.seek(n, 1)

        def value(kind, retain=True):
            formats = {0: "<B", 1: "<b", 2: "<H", 3: "<h", 4: "<I", 5: "<i",
                       6: "<f", 7: "<?", 10: "<Q", 11: "<q", 12: "<d"}
            if kind in formats:
                return unpack(formats[kind])
            if kind == 8:
                return string(retain)
            if kind == 9:
                subtype, count = unpack("<I"), unpack("<Q")
                if count > 10_000_000:
                    raise ValueError("Unreasonable metadata array")
                if subtype in formats:
                    f.seek(struct.calcsize(formats[subtype]) * count, 1)
                else:
                    for _ in range(count):
                        value(subtype, False)
                return {"array_count": count, "element_type": subtype}
            raise ValueError(f"Unknown GGUF metadata type {kind}")

        if f.read(4) != b"GGUF":
            raise ValueError("Not a GGUF file")
        version = unpack("<I")
        if version not in (2, 3):
            raise ValueError(f"Unsupported GGUF version {version}")
        tensors, metadata_count = unpack("<Q"), unpack("<Q")
        if tensors > 100_000 or metadata_count > 100_000:
            raise ValueError("Unreasonable GGUF counts")
        metadata = {}
        for _ in range(metadata_count):
            key = string()
            metadata[key] = value(unpack("<I"))
        # Block sizes and bytes for types expected in Q4_K_M models.
        blocks = {0: (1, 4), 1: (1, 2), 2: (32, 18), 3: (32, 20),
                  6: (32, 22), 7: (32, 24), 8: (32, 34), 10: (256, 84),
                  11: (256, 110), 12: (256, 144), 13: (256, 176), 14: (256, 210)}
        extents, types, names = [], {}, []
        for _ in range(tensors):
            name, ndims = string(), unpack("<I")
            if not 1 <= ndims <= 4:
                raise ValueError("Invalid tensor dimensions")
            dims = [unpack("<Q") for _ in range(ndims)]
            kind, offset = unpack("<I"), unpack("<Q")
            if any(x == 0 for x in dims):
                raise ValueError("Empty tensor")
            types[str(kind)] = types.get(str(kind), 0) + 1
            names.append(name)
            if kind in blocks:
                block, nbytes = blocks[kind]
                extents.append(offset + math.prod(dims) * nbytes // block)
        alignment = metadata.get("general.alignment", 32)
        data_start = (f.tell() + alignment - 1) // alignment * alignment
        expected_minimum = data_start + max(extents, default=0)
        if expected_minimum > size:
            raise ValueError(f"Truncated tensor data: need {expected_minimum}, found {size}")
    selected = {k: v for k, v in metadata.items() if k.startswith("general.") or
                k.endswith((".block_count", ".context_length", ".embedding_length",
                            ".head_count", ".head_count_kv"))}
    template = metadata.get("tokenizer.chat_template", "")
    with open(path, "rb") as f:
        digest = hashlib.file_digest(f, "sha256").hexdigest()
    return {"file": os.path.abspath(path), "bytes": size, "sha256": digest,
            "gguf_version": version, "tensor_count": tensors, "tensor_types": types,
            "data_start": data_start, "minimum_tensor_end": expected_minimum,
            "all_tensor_types_checked": all(int(t) in blocks for t in types),
            "metadata": selected, "chat_template_tail": template[-1600:],
            "first_tensor_names": names[:8]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="+")
    args = parser.parse_args()
    print(json.dumps([inspect(path) for path in args.files], ensure_ascii=False, indent=2))
