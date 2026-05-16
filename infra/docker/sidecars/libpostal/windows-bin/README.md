# libpostal Windows x64 Pre-compiled Binary

Cross-compiled from [openvenues/libpostal](https://github.com/openvenues/libpostal) using MinGW-w64 on Linux.

## Contents (`libpostal-win64.zip`)
- `bin/libpostal-1.dll` — the shared library (12MB)
- `lib/libpostal.a` — static library
- `lib/libpostal.dll.a` — import library for linking
- `include/libpostal/libpostal.h` — C header

## Usage on Windows

1. Extract `libpostal-win64.zip`
2. Add `bin/` to your `PATH` (so Python can find `libpostal-1.dll`)
3. Reassemble model data: `cd model-chunks && .\reassemble.ps1`
4. Set `LIBPOSTAL_DATA_DIR` to the extracted model data path
5. Install Python binding: `pip install postal`

```powershell
# Example setup
Expand-Archive libpostal-win64.zip -DestinationPath C:\libpostal
$env:PATH = "C:\libpostal\bin;" + $env:PATH
$env:LIBPOSTAL_DATA_DIR = "C:\libpostal\model-data"

# Run the sidecar
python server.py
```

## Build Info
- Source: libpostal master (latest)
- Compiler: x86_64-w64-mingw32-gcc (MinGW-w64 on Ubuntu 22.04)
- Target: Windows x64
- No external runtime dependencies (statically linked)
