#!/usr/bin/env python3
"""Download closed Binance Spot history for the 10 most liquid USDT pairs."""
import csv,json,os,re,time,urllib.error,urllib.parse,urllib.request
from datetime import datetime,timezone
from pathlib import Path
BASE="https://data-api.binance.vision/api/v3"
INTERVALS={"1h":3600000,"15m":900000,"5m":300000}
DAYS=int(os.environ.get("MULTI_HISTORY_DAYS","90"))
COUNT=int(os.environ.get("MULTI_HISTORY_COINS","10"))
OUT=Path(os.environ.get("MULTI_HISTORY_DIR","build/multi-coin-history"))
LIMIT=1000
EXCLUDED={"USDC","FDUSD","TUSD","USDP","DAI","USDE","USDS","BUSD","EUR","TRY","BRL","GBP","JPY","UST"}
def get_json(url):
 req=urllib.request.Request(url,headers={"User-Agent":"PredictionLab-MultiCoin-Validator/1.0"})
 for attempt in range(5):
  try:
   with urllib.request.urlopen(req,timeout=30) as response:return json.loads(response.read().decode("utf-8"))
  except urllib.error.HTTPError as exc:
   if exc.code not in (418,429,500,502,503,504) or attempt==4:raise
   time.sleep(2*(attempt+1))
  except (urllib.error.URLError,TimeoutError,json.JSONDecodeError):
   if attempt==4:raise
   time.sleep(1.5*(attempt+1))
def select_symbols():
 tickers=get_json(BASE+"/ticker/24hr")
 deny=re.compile(r"(UP|DOWN|BULL|BEAR|[0-9]+[LS])$")
 ranked=[]
 for t in tickers:
  s=t.get("symbol","")
  if not s.endswith("USDT"):continue
  base=s[:-4]
  if base in EXCLUDED or deny.search(base):continue
  try:volume=float(t["quoteVolume"])
  except (ValueError,KeyError):continue
  if volume>0:ranked.append((volume,s))
 return [s for _,s in sorted(ranked,reverse=True)[:COUNT]]
def page(symbol,interval,start,end):
 q=urllib.parse.urlencode({"symbol":symbol,"interval":interval,"startTime":start,"endTime":end,"limit":LIMIT})
 return get_json(BASE+"/klines?"+q)
def download(symbol,interval,start,end):
 rows=[];cursor=start;step=INTERVALS[interval]
 while cursor<=end:
  batch=page(symbol,interval,cursor,end)
  if not isinstance(batch,list):raise RuntimeError(f"Unexpected response for {symbol} {interval}")
  if not batch:break
  rows.extend(batch);next_cursor=int(batch[-1][0])+step
  if next_cursor<=cursor:raise RuntimeError(f"Pagination stalled: {symbol} {interval}")
  cursor=next_cursor;time.sleep(0.12)
 unique={int(r[0]):r for r in rows if int(r[6])<end}
 ordered=[unique[k] for k in sorted(unique)]
 for a,b in zip(ordered,ordered[1:]):
  if int(b[0])-int(a[0])!=step:raise RuntimeError(f"Gap {symbol} {interval}: {a[0]} -> {b[0]}")
 return ordered
def main():
 symbols=select_symbols()
 if len(symbols)<COUNT:raise RuntimeError(f"Only {len(symbols)} eligible USDT pairs; need {COUNT}")
 end=int(datetime.now(timezone.utc).timestamp()*1000);start=end-DAYS*86400000
 OUT.mkdir(parents=True,exist_ok=True)
 print("Multi-coin universe (top Spot USDT quote volume): "+", ".join(symbols),flush=True)
 for symbol in symbols:
  folder=OUT/symbol;folder.mkdir(parents=True,exist_ok=True)
  for interval in INTERVALS:
   rows=download(symbol,interval,start,end)
   minimum={"1h":1000,"15m":4000,"5m":12000}[interval]
   if len(rows)<minimum:raise RuntimeError(f"Insufficient {symbol} {interval}: {len(rows)} < {minimum}")
   path=folder/f"{interval}.csv"
   with path.open("w",newline="",encoding="utf-8") as f:
    w=csv.writer(f);w.writerow(["openTime","open","high","low","close","volume","closeTime"])
    for r in rows:w.writerow([r[0],r[1],r[2],r[3],r[4],r[5],r[6]])
   print(f"{symbol} {interval}: {len(rows)} closed candles",flush=True)
 print(f"Downloaded {len(symbols)} coins x 3 intervals; public Spot data, no API key.")
if __name__=="__main__":main()
