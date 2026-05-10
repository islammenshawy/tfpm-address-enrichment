Swiss addresses are tri-lingual (German, French, Italian) depending on canton.
- PST_CD is a 4-digit NPA/PLZ (e.g., 8001, 1204, 6900).
- TWN_NM is the city/town (e.g., "Zürich", "Genève", "Lugano").
- CTRY_SUB_DVSN is the canton abbreviation (e.g., ZH, GE, TI, BE, BS, VD). Derive from city if not explicit.
- STRT_NM is the street name. BLDG_NB is the house number.
- "Postfach" or "Case postale" means PO Box — box number goes to BLDG_NB, no STRT_NM.
- Locale may be de-CH, fr-CH, or it-CH — this affects street naming conventions.
