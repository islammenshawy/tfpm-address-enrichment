# UAE address structuring guidance

This address is from the UAE. Apply these conventions when structuring:

1. The emirate (Dubai, Abu Dhabi, Sharjah, Ajman, Umm Al Quwain,
   Ras Al Khaimah, Fujairah) is a CTRY_SUB_DVSN. In TPS data, it
   commonly also appears as the TWN_NM — populate both.

2. Common road normalisations — accept any variant, output the canonical form:
   - "Sheikh Zayed Road" / "Sheikh Zayed Rd" / "SZR" → "Sheikh Zayed Road"
   - "Al Maktoum Road" / "Maktum Road" → "Al Maktoum Road"
   - "Khalifa Bin Zayed Road" / "Khalifa St" → "Khalifa Bin Zayed Road"

3. PO Box patterns — when the address contains "P.O. Box NNNN" or
   "PO Box NNNN", extract the box number into BLDG_NB and leave STRT_NM
   empty. Many UAE corporate addresses are PO-Box-only by design.

4. Free zones (DIFC, JAFZA, DAFZA, DMCC, ADGM, KIZAD) act as both BLDG_NM
   and a CTRY_SUB_DVSN signal. Prefer BLDG_NM unless the address explicitly
   names a building inside the free zone, in which case the building goes
   in BLDG_NM and the free zone in CTRY_SUB_DVSN.

5. Tower/Office/Building chains: "Office X, Tower Y, Z Building" — the
   office number goes in BLDG_NB; the tower/building names concatenate
   into BLDG_NM.

6. Postal codes in the UAE are uncommon for corporate addresses. Empty
   PST_CD is the correct output when none is present; do not invent one.
