# Mainland China address structuring guidance

This address is from China. Apply these conventions:

1. Chinese addresses are written in REVERSE order from Western convention:
   province → city → district → road → number → building → unit.
   The structured fields, however, follow ISO 20022 conventions.

2. Province (省 sheng) maps to CTRY_SUB_DVSN. Common values:
   - Beijing (北京), Shanghai (上海), Tianjin (天津), Chongqing (重庆)
     are direct-controlled municipalities; province=city for these.
   - Provinces include Guangdong, Jiangsu, Zhejiang, Sichuan, Hubei, etc.

3. City (市 shi) maps to TWN_NM.

4. District (区 qu) is part of the address path but does NOT map cleanly
   to ISO 20022. Include it in BLDG_NM if it's part of a building's
   common name; otherwise leave it in the AdrLine fallback.

5. Road (路 lu / 街 jie / 大道 dadao) maps to STRT_NM. Accept English
   transliteration ("Nanjing West Road"), Pinyin ("Nanjing Xi Lu"), or
   Hanzi ("南京西路"); output the English transliteration form for
   consistency.

6. Building number is the road number (e.g. "1515 Nanjing West Road" → 1515).

7. Building name, when present, is in BLDG_NM. Tower designations (Tower
   A/B/C) are common; concatenate into BLDG_NM.

8. Postal codes are 6 digits. They are often present but not mandatory;
   if absent in the source, output empty PST_CD.

9. Room numbers ("Room 2801" / "Suite 2801") go into BLDG_NM, not BLDG_NB.
