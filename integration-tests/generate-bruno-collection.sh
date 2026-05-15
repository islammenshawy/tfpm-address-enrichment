#!/usr/bin/env bash
# Generates Bruno .bru request files from golden set fixtures.
# Each request includes expected field assertions from the golden data.
#
# Run from the integration-tests directory:
#   ./generate-bruno-collection.sh
#
# Then open the bruno/ folder in Bruno app.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
GOLDEN_DIR="$SCRIPT_DIR/src/test/resources/golden"
BRUNO_DIR="$SCRIPT_DIR/bruno/Golden-Set"

rm -rf "$BRUNO_DIR"
mkdir -p "$BRUNO_DIR"

# Use Python to generate all .bru files in one pass (faster, handles escaping properly)
python3 << 'PYEOF'
import json, os, sys

golden_dir = os.environ.get("GOLDEN_DIR", "src/test/resources/golden")
bruno_dir = os.environ.get("BRUNO_DIR", "bruno/Golden-Set")

count = 0
for root, dirs, files in sorted(os.walk(golden_dir)):
    for fname in sorted(files):
        if not fname.endswith(".json"):
            continue
        path = os.path.join(root, fname)
        with open(path) as f:
            fixture = json.load(f)

        fid = fixture.get("fixture_id", "unknown")
        country = fixture.get("country", "")
        raw = fixture.get("raw", "")
        hint = fixture.get("country_hint", "")
        locale = fixture.get("locale", "")
        expected = fixture.get("expected_fields", {})

        # Build expected field assertions
        assert_lines = []
        test_lines = []
        for field_name, field_val in expected.items():
            val = field_val.get("value", "") if isinstance(field_val, dict) else str(field_val)
            if not val:
                continue
            # Bruno assert syntax (case-insensitive comparison in tests block)
            test_lines.append(
                f'    const actual_{field_name} = (body.fields.{field_name}?.value || "").toLowerCase();'
            )
            escaped_val = val.replace("\\", "\\\\").replace('"', '\\"')
            test_lines.append(
                f'    expect(actual_{field_name}).to.eq("{escaped_val.lower()}");'
            )

        country_dir = os.path.join(bruno_dir, country)
        os.makedirs(country_dir, exist_ok=True)

        raw_json = json.dumps(raw)

        tests_block = ""
        if test_lines:
            tests_block = f'''tests {{
  test("{fid} — expected fields match golden set", function() {{
    const body = res.getBody();
    expect(body.outcome).to.be.oneOf(["SUCCESS", "REQUIRES_REVIEW", "UNSTRUCTURABLE"]);
{chr(10).join(test_lines)}
  }});
}}'''
        else:
            tests_block = f'''tests {{
  test("{fid} — returns structured response", function() {{
    const body = res.getBody();
    expect(body.outcome).to.be.oneOf(["SUCCESS", "REQUIRES_REVIEW", "UNSTRUCTURABLE"]);
    expect(body.overallConfidence).to.be.a("number");
  }});
}}'''

        bru_content = f'''meta {{
  name: {fid} — {country}
  type: http
  seq: {count + 1}
}}

post {{
  url: {{{{baseUrl}}}}/api/v1/enrich
  body: json
  auth: none
}}

headers {{
  Content-Type: application/json
  X-Correlation-Id: bruno-{fid}
}}

body:json {{
  {{
    "rawAddress": {raw_json},
    "countryHint": "{hint}",
    "locale": "{locale}"
  }}
}}

assert {{
  res.status: eq 200
  res.body.outcome: isString
  res.body.fields: isDefined
  res.body.overallConfidence: isNumber
}}

{tests_block}
'''

        out_path = os.path.join(country_dir, f"{fid}.bru")
        with open(out_path, "w") as out:
            out.write(bru_content)
        count += 1

print(f"Generated {count} Bruno requests in {bruno_dir}")
print(f"Open the bruno/ folder in Bruno app to run them.")
PYEOF
