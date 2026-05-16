# libpostal Model Data (Chunked)

Pre-downloaded libpostal model data, split into 50MB chunks for git storage.

## Contents
- `model-part-aa` through `model-part-be` — 31 chunks totaling ~1.5GB compressed (~3.7GB uncompressed)
- `reassemble.sh` — Unix/macOS/Git Bash reassembly script
- `reassemble.ps1` — Windows PowerShell reassembly script

## Reassembly

**macOS/Linux:**
```bash
chmod +x reassemble.sh
./reassemble.sh              # extracts to ../model-data/
./reassemble.sh /custom/path # extracts to custom location
```

**Windows (PowerShell):**
```powershell
.\reassemble.ps1                          # extracts to ..\model-data\
.\reassemble.ps1 -OutputDir C:\custom\path
```

**Windows (Git Bash):**
```bash
./reassemble.sh
```

## Docker Build
The Dockerfile automatically reassembles the model during `docker build`.
No manual reassembly needed for containerized usage.

## Source
Model data from [openvenues/libpostal](https://github.com/openvenues/libpostal) (MIT license).
Downloaded via `libpostal_data download all`.
