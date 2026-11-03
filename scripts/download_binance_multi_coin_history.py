#!/usr/bin/env python3
"""Download closed Binance Spot history for the most liquid eligible USDT pairs."""
import csv,json,os,re,time,urllib.error,urllib.parse,urllib.request
from datetime import datetime,timezone
from pathlib import Path

BASE="https://data-api.binance.vision/api/v3"
INTERVALS={"1h":3600000,"15m":900000,"5m":300000}
MINIMUM={"1h":1000,"15m":4000,"5m":12000}
DAYS=int(os.environ.get("MULTI_HISTORY_DAYS","90"))
COUNT=int(os.environ.get("MULTI_HISTORY_COINS","10"))
OUT=Path(os.environ.get("MULTI_HISTORY_DIR","build/multi-coin-history"))
LIMIT=1000
EXCLUDED={"USDC","FDUSD","TUSD","USDP","DAI","USDE","USDS","BUSD","EUR","TRY","BRL","GBP","JPY","UST"}

def get_json(url):
 req=urllib.request.Request(url,headers={"User-Agent":"PredictionLab-MultiCoin-Validator/1.0"})
 for attempt in range(5):
  try:
   with urllib.request.urlopen(req,timeout=30) as response:
    return json.loads(response.read().decode("utf-8"))
  except urllib.error.HTTPError as exc:
   if exc.code not in (418,429,500,502,503,504) or attempt==4:
    raise
   time.sleep(2*(attempt+1))
  except (urllib.error.URLError,TimeoutError,json.JSONDecodeError):
   if attempt==4:
    raise
   time.sleep(1.5*(attempt+1))

def ranked_symbols():
 tickers=get_json(BASE+"/ticker/24hr")
 deny=re.compile(r"(UP|DOWN|BULL|BEAR|[0-9]+[LS])$")
 ranked=[]
 for ticker in tickers:
  symbol=ticker.get("symbol","")
  if not symbol.endswith("USDT"):
   continue
  base=symbol[:-4]
  if base in EXCLUDED or deny.search(base):
   continue
  try:
   quote_volume=float(ticker["quoteVolume"])
  except (ValueError,KeyError,TypeError):
   continue
  if quote_volume>0:
   ranked.append((quote_volume,symbol))
 return [symbol for _,symbol in sorted(ranked,reverse=True)]

def page(symbol,interval,start,end):
 query=urllib.parse.urlencode({
  "symbol":symbol,"interval":interval,"startTime":start,"endTime":end,"limit":LIMIT
 })
 return get_json(BASE+"/klines?"+query)

def download(symbol,interval,start,end):
 rows=[]
 cursor=start
 step=INTERVALS[interval]
 while cursor<=end:
  batch=page(symbol,interval,cursor,end)
  if not isinstance(batch,list):
   raise RuntimeError(f"Unexpected response for {symbol} {interval}")
  if not batch:
   break
  rows.extend(batch)
  next_cursor=int(batch[-1][0])+step
  if next_cursor<=cursor:
   raise RuntimeError(f"Pagination stalled: {symbol} {interval}")
  cursor=next_cursor
  time.sleep(0.12)
 unique={int(row[0]):row for row in rows if int(row[6])<end}
 ordered=[unique[key] for key in sorted(unique)]
 for previous,current in zip(ordered,ordered[1:]):
  if int(current[0])-int(previous[0])!=step:
   raise RuntimeError(f"Gap {symbol} {interval}: {previous[0]} -> {current[0]}")
 return ordered

def write_dataset(symbol, datasets):
 folder=OUT/symbol
 folder.mkdir(parents=True,exist_ok=True)
 for interval,rows in datasets.items():
  path=folder/f"{interval}.csv"
  with path.open("w",newline="",encoding="utf-8") as handle:
   writer=csv.writer(handle)
   writer.writerow(["openTime","open","high","low","close","volume","closeTime"])
   for row in rows:
    writer.writerow([row[0],row[1],row[2],row[3],row[4],row[5],row[6]])

def main():
 end=int(datetime.now(timezone.utc).timestamp()*1000)
 start=end-DAYS*86400000
 OUT.mkdir(parents=True,exist_ok=True)
 accepted=[]
 candidates=ranked_symbols()
 print(f"Checking {len(candidates)} eligible USDT Spot candidates for {COUNT} complete datasets.",flush=True)
 for symbol in candidates:
  if len(accepted)>=COUNT:
   break
  datasets={}
  try:
   for interval in INTERVALS:
    rows=download(symbol,interval,start,end)
    if len(rows)<MINIMUM[interval]:
     raise ValueError(f"insufficient {interval}: {len(rows)} < {MINIMUM[interval]}")
    datasets[interval]=rows
   write_dataset(symbol,datasets)
   accepted.append(symbol)
   counts=", ".join(f"{interval}={len(rows)}" for interval,rows in datasets.items())
   print(f"ACCEPTED {symbol}: {counts}",flush=True)
  except (ValueError,RuntimeError,OSError) as exc:
   # Network failures for one pair must not prevent trying the next eligible pair.
   print(f"SKIPPED {symbol}: {exc}",flush=True)
  time.sleep(0.15)
 if len(accepted)<COUNT:
  raise RuntimeError(f"Only {len(accepted)}/{COUNT} pairs had complete, gap-free history; see SKIPPED diagnostics.")
 print("Validated multi-coin universe: "+", ".join(accepted),flush=True)
 print(f"Downloaded {len(accepted)} coins x 3 intervals; closed Binance Spot candles, public endpoints, no API key.")

if __name__=="__main__":
 main()
