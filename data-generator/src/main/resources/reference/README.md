# Reference prices

Real public prices that the synthetic data generator uses so holdings and trades are priced at
believable levels. Only the people and their accounts are made up.

| File | What | Source |
| --- | --- | --- |
| `funds.csv`, `fund_nav.csv` | 6 mutual fund schemes, NAV on about one date a month, 1 Oct 2025 to 1 Oct 2026 | AMFI NAV history, https://www.amfiindia.com/net-asset-value/nav-history |
| `stocks.csv`, `stock_close.csv` | 8 NSE equities, closing price (series EQ) on about one date a month | NSE CM-UDiFF Common Bhavcopy, https://www.nseindia.com/all-reports |

Spending category shares in the generator come from the MoSPI Household Consumption Expenditure
Survey 2023-24 (urban shares), https://mospi.gov.in.

Caveats:

- Prices between the monthly points are linearly interpolated (`PriceBook`), so they are not real
  daily prices. Outside the loaded span the nearest real price is used.
- The September 2026 bhavcopy was not available, so stock prices for that month are interpolated
  between August and 1 October.
- The source reports are published by AMFI, NSE and MoSPI for public use. Check their terms before
  redistributing more than this small extract.
- `data-generator/tools/extract_reference.py` rebuilds these files from the full downloads, which
  are not committed.
