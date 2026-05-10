German addresses use postcode-before-city format: Straße Hausnummer, PLZ Stadt.
- PST_CD is a 5-digit Postleitzahl (e.g., 60325, 10117).
- TWN_NM is the city (e.g., "Frankfurt am Main", "München", "Berlin").
- STRT_NM is the street name. "Straße", "Str.", "strasse" are all the same — normalize to full form if possible.
- BLDG_NB is the house number, which may include letter suffixes (e.g., "10a", "10-12").
- CTRY_SUB_DVSN is the Bundesland (federal state) but is rarely included in German addresses.
- "c/o" notation indicates care-of — the name after c/o goes to ADR_LINE, not BLDG_NM.
