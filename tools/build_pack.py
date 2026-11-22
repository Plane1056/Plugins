"""Validate source pack JSON and make a client-installable ZIP. Standard library only."""
import hashlib
import json
from pathlib import Path
import zipfile

root = Path(__file__).resolve().parents[1]
source = root / "resource-pack"
files = sorted(p for p in source.rglob("*") if p.is_file())
if not (source / "pack.mcmeta").is_file():
    raise SystemExit("resource-pack/pack.mcmeta is missing")
for path in files:
    if path.suffix in (".json", ".mcmeta"):
        json.loads(path.read_text(encoding="utf-8"))
out = root / "dist"
out.mkdir(exist_ok=True)
archive = out / "ApexGuns-ResourcePack-1.21.11.zip"
with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as z:
    for path in files:
        z.write(path, path.relative_to(source))
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
sha = hashlib.sha1(archive.read_bytes()).hexdigest()
(out / "resource-pack-sha1.txt").write_text(sha + "\n", encoding="ascii")
print(f"Built {archive.name}: {archive.stat().st_size} bytes; SHA-1 {sha}")
