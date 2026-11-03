#!/usr/bin/env python3
"""Download fully closed public Binance Spot BTCUSDT klines as CSV."""
import csv,json,os,time,urllib.error,urllib.parse,urllib.request
from datetime import datetime,timezone
from pathlib import Path
BASE="https://api.binance.com/api/v3/klines"
INTERVALS={"1h":3600000,"15m":900000,"5m":300000}
DAYS=int(os.environ.get("BTC_HISTORY_DAYS","90"));OUT=Path(os.environ.get("BTC_HISTORY_DIR","build/btc-history"));LIMIT=1000
def page(interval,start,end):
 q=urllib.parse.urlencode({"symbol":"BTCUSDT","interval":interval,"startTime":start,"endTime":end,"limit":LIMIT})
 req=urllib.request.Request(BASE+"?"+q,headers={"User-Agent":"PredictionLab-Historical-Validator/1.0"})
 for attempt in range(5):
  try:
   with urllib.request.urlopen(req,timeout=30) as response:data=json.loads(response.read().decode("utf-8"))
   if not isinstance(data,list):raise RuntimeError(f"Unexpected Binance response: {data}")
   return data
  except (urllib.error.URLError,TimeoutError,json.JSONDecodeError):
   if attempt==4:raise
   time.sleep(1.5*(attempt+1))
 return []
def download(interval,start,end):
 rows=[];cursor=start;step=INTERVALS[interval]
 while cursor<=end:
  batch=page(interval,cursor,end)
  if not batch:break
  rows.extend(batch);next_cursor=int(batch[-1][0])+step
  if next_cursor<=cursor:raise RuntimeError(f"Pagination stalled for {interval}")
  cursor=next_cursor;time.sleep(0.15)
 # Drop the currently forming candle: its closeTime is not before the snapshot end.
 unique={int(row[0]):row for row in rows if int(row[6])<end};ordered=[unique[k] for k in sorted(unique)]
 for a,b in zip(ordered,ordered[1:]):
  if int(b[0])-int(a[0])!=step:raise RuntimeError(f"Gap in {interval}: {a[0]} -> {b[0]}")
 return ordered
def main():
 OUT.mkdir(parents=True,exist_ok=True);end=int(datetime.now(timezone.utc).timestamp()*1000);start=end-DAYS*86400000
 print(f"Fetching BTCUSDT Spot klines: last {DAYS} days (closed candles only)")
 for interval in INTERVALS:
  rows=download(interval,start,end)
  if len(rows)<100:raise RuntimeError(f"Insufficient {interval} candles: {len(rows)}")
  path=OUT/f"BTCUSDT_{interval}.csv"
  with path.open("w",newline="",encoding="utf-8") as handle:
   writer=csv.writer(handle);writer.writerow(["openTime","open","high","low","close","volume","closeTime"])
   for row in rows:writer.writerow([row[0],row[1],row[2],row[3],row[4],row[5],row[6]])
  print(f"{interval}: {len(rows)} closed candles -> {path}")
 print(f"Data source: {BASE} (public Spot klines; no API key)")
if __name__=="__main__":main()
