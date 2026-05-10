Singapore addresses follow a strict format: Block/Street Number, Street Name, #Floor-Unit, Building Name, Singapore PostalCode.
- TWN_NM is always "Singapore" (city-state).
- PST_CD is a 6-digit number (e.g., 079903). Extract it with high confidence.
- Unit notation uses #XX-YY format (e.g., #12-34). This stays in ADR_LINE, not BLDG_NB.
- "Block" or "Blk" prefix indicates HDB public housing (e.g., "Blk 123").
- BLDG_NB is the street number or block number.
- CTRY_SUB_DVSN is not typically used for Singapore.
