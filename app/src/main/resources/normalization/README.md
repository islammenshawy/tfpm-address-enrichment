# Address Normalization Dictionaries

Source: [openvenues/libpostal](https://github.com/openvenues/libpostal/tree/master/resources/dictionaries)
License: MIT

## Format
Each file is a text file with one entry per line.
The leftmost string is the canonical/normalized form.
Synonyms are appended to the right, delimited by `|`.

Example: `avenue|av|ave|aven|avenu|avn`

## Coverage
- street-types/: 14 languages, 1094 entries
- building-types/: 6 languages

## Usage
The FieldNormalizer loads these at startup and uses them to resolve
abbreviation variants to a canonical form before consensus comparison.
This ensures "St" and "Street" don't create false disagreements.
