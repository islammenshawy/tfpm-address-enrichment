Hong Kong addresses do NOT have postal codes. PST_CD should be empty.
- TWN_NM is "Hong Kong" for the territory overall.
- CTRY_SUB_DVSN maps to districts: Central, Wan Chai, Tsim Sha Tsui, Kowloon, etc.
- Floor/unit patterns: "27/F" (floor), "Unit 2701", "Flat A" — extract floor/unit to ADR_LINE.
- BLDG_NM is critical (e.g., "Two International Finance Centre").
- BLDG_NB is the street number (e.g., "8" in "8 Finance Street").
- STRT_NM is the street name without the number.
