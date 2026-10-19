# Data licences and attribution

The software in this repository is licensed under Apache-2.0. **The data a running instance ingests is not.**
Each fact keeps the licence of the source it came from, and each fact links to that source. Operators who
redistribute data (for example a database dump or an API mirror) must follow these terms.

| Source | What is used | Licence | Attribution and conditions |
| --- | --- | --- | --- |
| [Wikidata](https://www.wikidata.org/) | Place identities, names and aliases (13 Indian languages), descriptions, coordinates, population, administrative relations, sitelink counts, image references | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) | No attribution required; the platform still links each place to its Wikidata item. |
| [English Wikivoyage](https://en.wikivoyage.org/) | Listing names, descriptions, prices, hours, addresses, phone numbers, websites and dates; summaries; transport, safety, customs and accommodation text; region lists | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | Credit “Wikivoyage contributors”, link to the article (each fact links the exact revision), state the licence. **Share-alike:** adapted text, including community corrections of it, must be shared under CC BY-SA 4.0. |
| [Open-Meteo](https://open-meteo.com/) Historical Weather API (ERA5 reanalysis) | Monthly averages derived from ten years of daily temperature and precipitation | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | Credit “Weather data by Open-Meteo.com”. The free API is for non-commercial use; commercial operators need an Open-Meteo API plan. |
| [Wikimedia Commons](https://commons.wikimedia.org/) | One destination image (hot-linked thumbnail) | The licence stated for each file (usually CC BY or CC BY-SA) | The author and licence are stored and shown with the image, linking to the file page. Images without a stated free licence are not used. |
| [OpenStreetMap](https://www.openstreetmap.org/copyright) (Geofabrik India extract) | Named points of interest in travel categories: name, category, coordinates (way centroids for buildings), address, phone, website, opening hours, cuisine, heritage tags; Wikidata links of place nodes | [ODbL 1.0](https://opendatacommons.org/licenses/odbl/1-0/) | Credit “© OpenStreetMap contributors”. **Share-alike for databases:** a redistributed POI database derived from OSM (for example a dump of `poi` / `poi_source_record`) must be offered under ODbL. Pages that only display the data are Produced Works and need the attribution. |
| [Overture Maps](https://docs.overturemaps.org/attribution/) Places | Places within about 12 km of indexed destinations, confidence ≥ 0.6, in travel categories: name, category, coordinates, address, phone, website, brand, contributing datasets | Per contributing dataset: [CDLA Permissive 2.0](https://cdla.dev/permissive-2-0/) (Meta, Microsoft, PinMeTo, DAC and others), [Apache 2.0](https://www.apache.org/licenses/LICENSE-2.0) (Foursquare), [CC0](https://creativecommons.org/publicdomain/zero/1.0/) (AllThePlaces) | Credit “© Overture Maps Foundation” and the contributing datasets. Redistribution of Foursquare-derived records must include Overture’s Foursquare NOTICE. Each stored record keeps its list of contributing datasets so the terms can be traced. |
| Protected monuments with ASI numbers ([Wikidata P1371](https://www.wikidata.org/wiki/Property:P1371)) | Monument identities, ASI numbers, heritage designation, coordinates, administrative area | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) (Wikidata statements) | No attribution required; each record links its Wikidata item. Nothing is copied from ASI web pages or PDFs. |
| Community contributions | Corrections, confirmations, new places and tips | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | Contributors accept this licence before their first contribution and choose display-name or anonymous credit. |

## What is stored

- Extracted facts with a link to the source revision, the extraction method, the observation date recorded by
  the source and the retrieval time. Raw API responses and full articles are not stored.
- For Wikivoyage, the text of each listing description or section that is shown (as permitted by CC BY-SA) and
  its revision permalink. Editor usernames are not stored.
- For Open-Meteo, only the derived monthly aggregates and the reproducible request URL.
- For OpenStreetMap, Overture and the ASI monument records, one row per source record with its source id,
  version, release, original attributes (OSM tags, Overture columns, Wikidata statements) and the canonical
  place it was matched to. Downloaded release files are deleted after import unless `INGESTION_KEEP_DOWNLOADS`
  is set.
- Mapped places are shown in guides only as clearly labelled, unreviewed map data, and only from sources whose
  latest quality evaluation did not fail.

## What this repository contains

No ingested data. The fixtures in `backend/src/test/resources/fixtures` are synthetic Wikivoyage-format text
written for the automated tests (they describe the fictional “Testville”).

## Removing a source

Every fact is tagged with its source (`evidence.source_id`), and every mapped place record with its
`poi_source_record.source_id`, so all content from one source can be located,
disabled (`PATCH /api/admin/sources/{id}`) or removed with a single query if a source’s terms change.
