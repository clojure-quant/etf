# etf

Scrape the iShares US ETF list — ticker, name, inception date and net assets — from:

<https://www.ishares.com/us/products/etf-investments#/?productView=etf&pageNumber=1&sortColumn=totalNetAssets&sortDirection=desc&search=ishares&dataView=keyFacts>

## How it works

That page is an Angular app, so the fund rows are **not** in the HTML. The rows come
from the iShares product-screener JSON API that the app calls:

```
https://www.ishares.com/us/product-screener/product-screener-v3.1.jsn
  ?dcrPath=/templatedata/config/product-screener-v3/data/en/us-ishares/ishares-product-screener-backend-config
  &siteEntryPassthrough=true&productView=etf&sortColumn=totalNetAssets&sortDirection=desc&pageNumber=1
```

A single request returns every matching fund (526 as of writing), keyed by portfolio
id. Two scraping sources are provided:

- **`api`** (default) — one request, parses the screener JSON. Fast and complete.
- **`html`** — visits each fund page and parses it with
  [`org.clj-commons/hickory`](https://github.com/clj-commons/hickory) `0.7.7`, reading
  the embedded schema.org JSON-LD (`<script type="application/ld+json">`) for
  `ticker`, `fundName`, `Fund Inception` and `Net Assets of Fund`. Slower, but shows
  how to scrape the rendered pages directly.

## Requirements

- Clojure CLI (`clojure`) 1.11+
- Java 11+ (uses `java.net.http`, no extra HTTP dependency)

## Usage

```bash
# JSON of all ETFs to stdout (default: api source, json format)
clojure -M:run

# Write JSON to a file
clojure -M:run -o etfs.json

# CSV / EDN / aligned table
clojure -M:run -f csv   -o etfs.csv
clojure -M:run -f edn   -o etfs.edn
clojure -M:run -f table

# Scrape rendered fund pages with hickory (limit for a quick run)
clojure -M:run -s html -n 10 -f table -o etfs.html.json

# Download the whole ETF universe to data/etf-universe.edn
clojure -X:download-universe
```

Options:

| Flag | Description | Default |
|------|-------------|---------|
| `-s, --source api\|html` | `api` = screener JSON, `html` = per-page hickory scrape | `api` |
| `-f, --format json\|edn\|csv\|table` | output format | `json` |
| `-o, --out FILE` | write to FILE instead of stdout | stdout |
| `-n, --limit N` | only scrape the first N funds (html source) | all |
| `-u, --url URL` | override the screener API URL | iShares US ETF URL |
| `-h, --help` | show help | |

Each record has the requested fields plus a numeric value and the fund page link:

```json
{
  "portfolio-id": 239726,
  "ticker": "IVV",
  "name": "iShares Core S&P 500 ETF",
  "inception-date": "May 15, 2000",
  "net-assets": "867,098,495,687",
  "net-assets-value": 867098495687.4,
  "product-url": "/us/products/239726/ishares-core-s-p-500-etf"
}
```

## Layout

```
deps.edn
src/etf/http.clj       minimal java.net.http GET helper
src/etf/screener.clj   product-screener JSON API -> fund maps
src/etf/product.clj    hickory fund-page scraper (schema.org JSON-LD)
src/etf/core.clj       CLI, sources and output formats
test/etf/*             unit tests with inline fixtures (no network)
```

## Tests

```bash
clojure -M:test
```