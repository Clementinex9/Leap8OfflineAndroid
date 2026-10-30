Place one SoC-matched Qualcomm Genie bundle here before building the NPU edition:
- genie_config.json
- tokenizer.json
- metadata.json (required), including soc_model, qairt_version, prompt_format,
  and file_sha256 entries for genie_config.json, tokenizer.json and every .bin
- every .bin context binary named by genie_config.json

The model binaries are intentionally not redistributed because they are large,
device-specific, and governed by their own model and QAIRT licenses.
