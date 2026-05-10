UK addresses have highly structured postcodes (e.g., E14 5JP, SW1A 1AA, EC2N 4AJ).
- PST_CD is the UK postcode — extract with high confidence. Format: outward code (area+district) + space + inward code (sector+unit).
- TWN_NM is the city/town (e.g., London, Edinburgh, Manchester).
- CTRY_SUB_DVSN is generally NOT used in UK addresses (no state/province equivalent in address format).
- STRT_NM is the street name. BLDG_NB is the house/building number.
- District names like "Canary Wharf", "Mayfair" are NOT CTRY_SUB_DVSN — they stay in ADR_LINE.
